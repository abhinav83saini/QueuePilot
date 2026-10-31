package com.queuepilot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.queuepilot.executor.JobExecutionResult;
import com.queuepilot.executor.JobExecutorRegistry;
import com.queuepilot.executor.JsonSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class JobSchedulerService {

    private static final Logger log =
            LoggerFactory.getLogger(JobSchedulerService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobExecutorRegistry executors;
    private final int poolSize;
    private final int defaultCapacity;
    private final double priorityAgingIntervalSeconds;

    private final List<Long> registeredWorkerIds = new CopyOnWriteArrayList<>();
    private final ExecutorService workerThreadPool;

    @Autowired
    public JobSchedulerService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JobExecutorRegistry executors,
            @Value("${queuepilot.workers.pool-size:4}") int poolSize,
            @Value("${queuepilot.workers.default-capacity:1}") int defaultCapacity,
            @Value("${queuepilot.scheduler.priority-aging-interval-seconds:10.0}") double priorityAgingIntervalSeconds) {

        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.executors = executors;
        this.poolSize = Math.max(1, poolSize);
        this.defaultCapacity = Math.max(1, defaultCapacity);
        this.priorityAgingIntervalSeconds = priorityAgingIntervalSeconds > 0 ? priorityAgingIntervalSeconds : 10.0;

        AtomicInteger workerThreadNumber = new AtomicInteger(1);
        this.workerThreadPool = Executors.newFixedThreadPool(this.poolSize, r -> {
            Thread t = new Thread(r, "queuepilot-worker-thread-" + workerThreadNumber.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public JobSchedulerService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JobExecutorRegistry executors) {
        this(jdbc, transactionManager, executors, 4, 1, 10.0);
    }

    @PostConstruct
    public void registerWorkers() {
        Set<String> supportedTypes = executors.supportedJobTypes();

        for (int i = 1; i <= poolSize; i++) {
            String name = "worker-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);

            Long id = jdbc.queryForObject("""
                    INSERT INTO workers
                        (name, health_status, capacity, last_heartbeat)
                    VALUES (?, 'HEALTHY', ?, NOW())
                    RETURNING worker_id
                    """, Long.class, name, defaultCapacity);

            if (id != null) {
                registeredWorkerIds.add(id);

                for (String cap : supportedTypes) {
                    jdbc.update("""
                            INSERT INTO worker_capabilities (worker_id, capability)
                            VALUES (?, ?)
                            ON CONFLICT DO NOTHING
                            """, id, cap);
                }
                log.info("Registered worker {} ({}) with capacity {} and {} capabilities",
                        id, name, defaultCapacity, supportedTypes.size());
            }
        }
    }

    @PreDestroy
    public void unregisterWorkers() {
        workerThreadPool.shutdown();
        try {
            if (!workerThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (Long id : registeredWorkerIds) {
            try {
                jdbc.update("""
                        UPDATE workers
                        SET health_status = 'OFFLINE',
                            last_heartbeat = NOW()
                        WHERE worker_id = ?
                        """, id);
                log.info("Worker {} marked offline", id);
            } catch (Exception ex) {
                log.warn("Could not mark worker {} offline: {}", id, ex.getMessage());
            }
        }
    }

    public List<Long> getRegisteredWorkerIds() {
        return List.copyOf(registeredWorkerIds);
    }

    public double getPriorityAgingIntervalSeconds() {
        return priorityAgingIntervalSeconds;
    }

    private static final String CLAIM_SQL = """
            WITH policy AS (
                SELECT COALESCE(
                    (
                        SELECT active_policy
                        FROM scheduler_settings
                        ORDER BY setting_id
                        LIMIT 1
                    ),
                    'FCFS'
                ) AS active_policy
            ),
            candidate AS (
                SELECT j.job_id
                FROM jobs j
                CROSS JOIN policy p
                WHERE j.status = 'QUEUED'
                  AND j.available_at <= NOW()
                  AND (
                      j.scheduled_at IS NULL
                      OR j.scheduled_at <= NOW()
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM job_dependencies d
                      JOIN jobs prerequisite
                        ON prerequisite.job_id = d.depends_on_job_id
                      WHERE d.job_id = j.job_id
                        AND prerequisite.status <> 'COMPLETED'
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM worker_capabilities wc
                      WHERE wc.worker_id = ?
                        AND UPPER(wc.capability) = UPPER(j.job_type)
                  )
                ORDER BY
                    CASE
                        WHEN p.active_policy = 'PRIORITY_AGING'
                        THEN (j.priority + (EXTRACT(EPOCH FROM (NOW() - j.available_at)) / ?))
                        WHEN p.active_policy = 'PRIORITY'
                        THEN j.priority
                    END DESC NULLS LAST,
                    CASE
                        WHEN p.active_policy = 'SJF'
                        THEN COALESCE(
                            j.estimated_duration_ms,
                            9223372036854775807
                        )
                    END ASC NULLS LAST,
                    j.created_at ASC
                LIMIT 1
                FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE jobs j
            SET status = 'RUNNING',
                started_at = NOW(),
                completed_at = NULL,
                error_message = NULL,
                progress = 10
            FROM candidate c
            WHERE j.job_id = c.job_id
            RETURNING
                j.job_id,
                j.job_type,
                j.input_data::text AS input_data,
                j.max_retries,
                j.retry_delay_seconds
            """;

    @Scheduled(fixedDelayString =
            "${queuepilot.scheduler.poll-delay-ms:1000}")
    public void pollQueue() {
        if (registeredWorkerIds.isEmpty()) {
            return;
        }

        try {
            // Periodic heartbeats for all registered healthy workers
            for (Long wid : registeredWorkerIds) {
                jdbc.update("""
                        UPDATE workers
                        SET last_heartbeat = NOW()
                        WHERE worker_id = ? AND health_status = 'HEALTHY'
                        """, wid);
            }

            // Extend leases for running attempts on healthy workers
            jdbc.update("""
                    UPDATE execution_attempts ea
                    SET lease_expires_at = NOW() + INTERVAL '30 seconds'
                    FROM workers w
                    WHERE ea.worker_id = w.worker_id
                      AND ea.status = 'RUNNING'
                      AND w.health_status = 'HEALTHY'
                    """);

            recoverExpiredLeases();
            recoverOrphanedRunningJobs();
            failJobsWithFailedDependencies();
            activateScheduledJobs();
            unblockReadyJobs();

            List<AvailableWorker> availableWorkers = getAvailableWorkers();

            for (AvailableWorker worker : availableWorkers) {
                for (int slot = 0; slot < worker.availableSlots(); slot++) {
                    List<ClaimedJob> claimed = jdbc.query(
                            CLAIM_SQL,
                            (rs, row) -> new ClaimedJob(
                                    rs.getLong("job_id"),
                                    rs.getString("job_type"),
                                    rs.getString("input_data"),
                                    rs.getInt("max_retries"),
                                    rs.getInt("retry_delay_seconds")
                            ),
                            worker.workerId(),
                            priorityAgingIntervalSeconds
                    );

                    if (claimed.isEmpty()) {
                        break;
                    }

                    ClaimedJob job = claimed.get(0);
                    Long assignedWorkerId = worker.workerId();
                    workerThreadPool.submit(() -> processJob(job, assignedWorkerId));
                }
            }

        } catch (Exception ex) {
            log.error("Scheduler polling failed", ex);
        }
    }

    private List<AvailableWorker> getAvailableWorkers() {
        return jdbc.query("""
                SELECT w.worker_id,
                       (w.capacity - COALESCE(active.cnt, 0)) AS available_slots
                FROM workers w
                LEFT JOIN (
                    SELECT worker_id, COUNT(*) AS cnt
                    FROM execution_attempts
                    WHERE status = 'RUNNING'
                    GROUP BY worker_id
                ) active ON active.worker_id = w.worker_id
                WHERE w.health_status = 'HEALTHY'
                  AND (w.capacity - COALESCE(active.cnt, 0)) > 0
                ORDER BY w.worker_id ASC
                """,
                (rs, row) -> new AvailableWorker(
                        rs.getLong("worker_id"),
                        rs.getInt("available_slots")
                ));
    }

    private void updateJobProgress(Long jobId, int progress) {
        try {
            jdbc.update("""
                    UPDATE jobs
                    SET progress = ?
                    WHERE job_id = ? AND status = 'RUNNING'
                    """, progress, jobId);
        } catch (Exception ex) {
            log.warn("Could not update progress for job {}: {}", jobId, ex.getMessage());
        }
    }

    private void processJob(ClaimedJob job, Long assignedWorkerId) {
        Long attemptId = null;
        int attemptNumber = 0;
        int attemptInCycle = 0;

        try {
            attemptNumber = getNextAttemptNumber(job.id());
            attemptInCycle = getCurrentCycleAttemptCount(job.id()) + 1;

            final int globalNumber = attemptNumber;
            final int cycleNumber = attemptInCycle;

            attemptId = transactions.execute(transactionStatus -> {
                Long id = jdbc.queryForObject("""
                        INSERT INTO execution_attempts
                            (job_id, worker_id, attempt_number, status,
                             lease_expires_at, started_at)
                        VALUES (?, ?, ?, 'RUNNING',
                                NOW() + INTERVAL '30 seconds', NOW())
                        RETURNING attempt_id
                        """,
                        Long.class,
                        job.id(),
                        assignedWorkerId,
                        globalNumber
                );

                addEvent(
                        job.id(),
                        "JOB_STARTED",
                        "{\"attemptNumber\":" + globalNumber +
                        ",\"attemptInCycle\":" + cycleNumber +
                        ",\"workerId\":" + assignedWorkerId + "}"
                );

                return id;
            });

            if (attemptId == null) {
                throw new IllegalStateException(
                        "Could not create an execution attempt");
            }

            // Report progress during execution (SRS F9, U4)
            updateJobProgress(job.id(), 35);

            // Execute built-in task on the worker thread
            JobExecutionResult result = executors.execute(
                    job.jobType(),
                    JsonSupport.parse(job.inputData()));
            String output = result.toJson();

            updateJobProgress(job.id(), 85);

            final Long savedAttemptId = attemptId;
            final String savedOutput = output;

            transactions.executeWithoutResult(transactionStatus -> {
                int jobUpdated = jdbc.update("""
                        UPDATE jobs
                        SET status = 'COMPLETED',
                            progress = 100,
                            output_data = CAST(? AS jsonb),
                            error_message = NULL,
                            completed_at = NOW()
                        WHERE job_id = ?
                          AND status = 'RUNNING'
                        """, savedOutput, job.id());

                if (jobUpdated != 1) {
                    throw new IllegalStateException(
                            "Job state changed before completion");
                }

                int attemptUpdated = jdbc.update("""
                        UPDATE execution_attempts
                        SET status = 'COMPLETED',
                            finished_at = NOW(),
                            output_data = CAST(? AS jsonb),
                            error_message = NULL
                        WHERE attempt_id = ?
                          AND status = 'RUNNING'
                        """, savedOutput, savedAttemptId);

                if (attemptUpdated != 1) {
                    throw new IllegalStateException(
                            "Execution attempt was not RUNNING");
                }

                addEvent(
                        job.id(),
                        "JOB_COMPLETED",
                        "{\"attemptNumber\":" + globalNumber + "}"
                );
            });

            log.info("Job {} completed successfully by worker {}", job.id(), assignedWorkerId);

        } catch (Exception ex) {
            String error = ex.getMessage() == null
                    ? "Job execution failed"
                    : ex.getMessage();

            log.error("Job {} failed during processing by worker {}", job.id(), assignedWorkerId, ex);

            if (attemptId == null) {
                // Failure occurred before an execution attempt was persisted.
                try {
                    transactions.executeWithoutResult(transactionStatus -> {
                        jdbc.update("""
                                UPDATE jobs
                                SET status = 'FAILED',
                                    error_message = ?,
                                    completed_at = NOW()
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                """, error, job.id());

                        addEvent(
                                job.id(),
                                "JOB_FAILED",
                                "{\"reason\":\"ATTEMPT_CREATION_FAILED\"}"
                        );
                    });
                } catch (Exception recoveryError) {
                    log.error(
                            "Could not recover job {} after attempt creation failed",
                            job.id(), recoveryError);
                }

                return;
            }

            final Long failedAttemptId = attemptId;
            final int failedAttemptNumber = attemptNumber;
            final int failedCycleNumber = attemptInCycle;
            final String failureMessage = error;

            try {
                transactions.executeWithoutResult(transactionStatus -> {
                    jdbc.update("""
                            UPDATE execution_attempts
                            SET status = 'FAILED',
                                finished_at = NOW(),
                                error_message = ?
                            WHERE attempt_id = ?
                              AND status = 'RUNNING'
                            """, failureMessage, failedAttemptId);

                    if (failedCycleNumber <= job.maxRetries()) {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'QUEUED',
                                    progress = 0,
                                    error_message = ?,
                                    available_at =
                                        NOW() + make_interval(secs => ?),
                                    started_at = NULL,
                                    completed_at = NULL
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                """,
                                failureMessage,
                                job.retryDelaySeconds(),
                                job.id()
                        );

                        if (updated > 0) {
                            addEvent(
                                    job.id(),
                                    "JOB_RETRY_SCHEDULED",
                                    "{\"attemptNumber\":" +
                                    failedAttemptNumber +
                                    ",\"attemptInCycle\":" +
                                    failedCycleNumber +
                                    ",\"retryDelaySeconds\":" +
                                    job.retryDelaySeconds() + "}"
                            );

                            log.warn(
                                    "Job {} failed; attempt {} in cycle {}, retry scheduled",
                                    job.id(),
                                    failedAttemptNumber,
                                    failedCycleNumber);
                        }

                    } else {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'FAILED',
                                    error_message = ?,
                                    completed_at = NOW()
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                """, failureMessage, job.id());

                        if (updated > 0) {
                            addEvent(
                                    job.id(),
                                    "JOB_FAILED",
                                    "{\"attemptNumber\":" +
                                    failedAttemptNumber +
                                    ",\"attemptInCycle\":" +
                                    failedCycleNumber + "}"
                            );

                            log.error(
                                    "Job {} failed after exhausting retries",
                                    job.id());
                        }
                    }
                });

            } catch (Exception recoveryError) {
                log.error(
                        "Could not persist failure state for job {}",
                        job.id(), recoveryError);
            }
        }
    }

    private int getNextAttemptNumber(Long jobId) {
        Long number = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1
                FROM execution_attempts
                WHERE job_id = ?
                """, Long.class, jobId);

        return number == null ? 1 : number.intValue();
    }

    /*
     * Manual retry starts a new retry cycle.
     * The global attempt_number remains unique across all cycles,
     * while this count determines the retry allowance for the
     * current cycle.
     */
    private int getCurrentCycleAttemptCount(Long jobId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM execution_attempts ea
                WHERE ea.job_id = ?
                  AND ea.started_at >= COALESCE(
                      (
                          SELECT MAX(e.created_at)
                          FROM job_events e
                          WHERE e.job_id = ?
                            AND e.event_type = 'JOB_MANUAL_RETRY'
                      ),
                      '-infinity'::timestamptz
                  )
                """, Long.class, jobId, jobId);

        return count == null ? 0 : count.intValue();
    }

    private void recoverExpiredLeases() {
        List<ExpiredAttempt> expired = jdbc.query("""
                SELECT
                    ea.attempt_id,
                    ea.worker_id,
                    j.job_id,
                    j.max_retries,
                    j.retry_delay_seconds
                FROM execution_attempts ea
                JOIN jobs j ON j.job_id = ea.job_id
                WHERE ea.status = 'RUNNING'
                  AND ea.lease_expires_at < NOW()
                  AND j.status = 'RUNNING'
                ORDER BY ea.attempt_id
                """,
                (rs, row) -> new ExpiredAttempt(
                        rs.getLong("attempt_id"),
                        rs.getObject("worker_id", Long.class),
                        rs.getLong("job_id"),
                        rs.getInt("max_retries"),
                        rs.getInt("retry_delay_seconds")
                ));

        if (expired == null || expired.isEmpty()) {
            return;
        }

        for (ExpiredAttempt attempt : expired) {
            try {
                // SRS F11: When lease expires, mark worker unavailable (UNHEALTHY)
                if (attempt.workerId() != null) {
                    jdbc.update("""
                            UPDATE workers
                            SET health_status = 'UNHEALTHY'
                            WHERE worker_id = ?
                            """, attempt.workerId());
                    log.warn("Worker {} marked UNHEALTHY due to expired lease on attempt {}",
                            attempt.workerId(), attempt.attemptId());
                }

                transactions.executeWithoutResult(transactionStatus -> {
                    int changed = jdbc.update("""
                            UPDATE execution_attempts
                            SET status = 'FAILED',
                                finished_at = NOW(),
                                error_message = 'Worker lease expired'
                            WHERE attempt_id = ?
                              AND status = 'RUNNING'
                              AND lease_expires_at < NOW()
                            """, attempt.attemptId());

                    if (changed == 0) {
                        return;
                    }

                    int cycleCount =
                            getCurrentCycleAttemptCount(attempt.jobId());

                    if (cycleCount <= attempt.maxRetries()) {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'QUEUED',
                                    progress = 0,
                                    error_message = 'Worker lease expired; retry scheduled',
                                    available_at =
                                        NOW() + make_interval(secs => ?),
                                    started_at = NULL,
                                    completed_at = NULL
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                """,
                                attempt.retryDelaySeconds(),
                                attempt.jobId());

                        if (updated > 0) {
                            addEvent(
                                    attempt.jobId(),
                                    "JOB_RETRY_SCHEDULED",
                                    "{\"reason\":\"WORKER_LEASE_EXPIRED\",\"workerId\":" + attempt.workerId() + "}"
                            );
                        }

                    } else {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'FAILED',
                                    error_message = 'Worker lease expired; retry limit reached',
                                    completed_at = NOW()
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                """, attempt.jobId());

                        if (updated > 0) {
                            addEvent(
                                    attempt.jobId(),
                                    "JOB_FAILED",
                                    "{\"reason\":\"WORKER_LEASE_EXPIRED\",\"workerId\":" + attempt.workerId() + "}"
                            );
                        }
                    }
                });

            } catch (Exception ex) {
                log.error(
                        "Could not recover expired attempt {}",
                        attempt.attemptId(), ex);
            }
        }
    }

    /*
     * Repairs old RUNNING jobs that have no active RUNNING attempt.
     */
    private void recoverOrphanedRunningJobs() {
        List<OrphanedJob> orphaned = jdbc.query("""
                SELECT
                    j.job_id,
                    j.max_retries,
                    j.retry_delay_seconds
                FROM jobs j
                WHERE j.status = 'RUNNING'
                  AND j.started_at < NOW() - INTERVAL '35 seconds'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM execution_attempts ea
                      WHERE ea.job_id = j.job_id
                        AND ea.status = 'RUNNING'
                  )
                ORDER BY j.job_id
                """,
                (rs, row) -> new OrphanedJob(
                        rs.getLong("job_id"),
                        rs.getInt("max_retries"),
                        rs.getInt("retry_delay_seconds")
                ));

        if (orphaned == null || orphaned.isEmpty()) {
            return;
        }

        for (OrphanedJob job : orphaned) {
            try {
                transactions.executeWithoutResult(transactionStatus -> {
                    int cycleCount =
                            getCurrentCycleAttemptCount(job.id());

                    if (cycleCount <= job.maxRetries()) {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'QUEUED',
                                    progress = 0,
                                    error_message = 'Recovered stale RUNNING job; retry scheduled',
                                    available_at =
                                        NOW() + make_interval(secs => ?),
                                    started_at = NULL,
                                    completed_at = NULL
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM execution_attempts ea
                                      WHERE ea.job_id = jobs.job_id
                                        AND ea.status = 'RUNNING'
                                  )
                                """,
                                job.retryDelaySeconds(),
                                job.id());

                        if (updated > 0) {
                            addEvent(
                                    job.id(),
                                    "JOB_RECOVERED",
                                    "{\"reason\":\"NO_ACTIVE_ATTEMPT\"}"
                            );

                            log.warn(
                                    "Recovered orphaned job {} by requeueing it",
                                    job.id());
                        }

                    } else {
                        int updated = jdbc.update("""
                                UPDATE jobs
                                SET status = 'FAILED',
                                    error_message = 'Recovered stale RUNNING job; retry limit reached',
                                    completed_at = NOW()
                                WHERE job_id = ?
                                  AND status = 'RUNNING'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM execution_attempts ea
                                      WHERE ea.job_id = jobs.job_id
                                        AND ea.status = 'RUNNING'
                                  )
                                """, job.id());

                        if (updated > 0) {
                            addEvent(
                                    job.id(),
                                    "JOB_FAILED",
                                    "{\"reason\":\"ORPHANED_JOB_RETRY_LIMIT\"}"
                            );
                        }
                    }
                });

            } catch (Exception ex) {
                log.error(
                        "Could not recover orphaned job {}",
                        job.id(), ex);
            }
        }
    }

    private void failJobsWithFailedDependencies() {
        List<Long> failedJobs = jdbc.query("""
                UPDATE jobs j
                SET status = 'FAILED',
                    error_message =
                        'A prerequisite job failed or was cancelled',
                    completed_at = NOW()
                WHERE j.status IN ('QUEUED', 'SCHEDULED', 'BLOCKED')
                  AND EXISTS (
                      SELECT 1
                      FROM job_dependencies d
                      JOIN jobs prerequisite
                        ON prerequisite.job_id = d.depends_on_job_id
                      WHERE d.job_id = j.job_id
                        AND prerequisite.status IN ('FAILED', 'CANCELLED')
                  )
                RETURNING j.job_id
                """,
                (rs, row) -> rs.getLong("job_id"));

        if (failedJobs != null) {
            for (Long jobId : failedJobs) {
                addEvent(
                        jobId,
                        "JOB_FAILED",
                        "{\"reason\":\"PREREQUISITE_FAILED\"}"
                );
            }
        }
    }

    private void activateScheduledJobs() {
        List<Long> activatedJobs = jdbc.query("""
                UPDATE jobs j
                SET status = 'QUEUED'
                WHERE j.status = 'SCHEDULED'
                  AND (j.scheduled_at IS NULL OR j.scheduled_at <= NOW())
                  AND NOT EXISTS (
                      SELECT 1
                      FROM job_dependencies d
                      JOIN jobs prerequisite
                        ON prerequisite.job_id = d.depends_on_job_id
                      WHERE d.job_id = j.job_id
                        AND prerequisite.status <> 'COMPLETED'
                  )
                RETURNING j.job_id
                """,
                (rs, row) -> rs.getLong("job_id"));

        if (activatedJobs != null) {
            for (Long jobId : activatedJobs) {
                addEvent(
                        jobId,
                        "JOB_ACTIVATED",
                        "{\"reason\":\"SCHEDULED_TIME_REACHED\"}"
                );
            }
        }
    }

    private void unblockReadyJobs() {
        List<Long> unblockedJobs = jdbc.query("""
                UPDATE jobs j
                SET status = CASE
                        WHEN j.scheduled_at IS NOT NULL AND j.scheduled_at > NOW() THEN 'SCHEDULED'
                        ELSE 'QUEUED'
                    END
                WHERE j.status = 'BLOCKED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM job_dependencies d
                      JOIN jobs prerequisite
                        ON prerequisite.job_id = d.depends_on_job_id
                      WHERE d.job_id = j.job_id
                        AND prerequisite.status <> 'COMPLETED'
                  )
                RETURNING j.job_id
                """,
                (rs, row) -> rs.getLong("job_id"));

        if (unblockedJobs != null) {
            for (Long jobId : unblockedJobs) {
                addEvent(
                        jobId,
                        "JOB_UNBLOCKED",
                        "{\"reason\":\"ALL_PREREQUISITES_COMPLETED\"}"
                );
            }
        }
    }

    private void addEvent(
            Long jobId,
            String eventType,
            String details) {

        jdbc.update("""
                INSERT INTO job_events
                    (job_id, event_type, details)
                VALUES (?, ?, CAST(? AS jsonb))
                """, jobId, eventType, details);
    }

    public record ClaimedJob(
            Long id,
            String jobType,
            String inputData,
            int maxRetries,
            int retryDelaySeconds) {
    }

    public record ExpiredAttempt(
            Long attemptId,
            Long workerId,
            Long jobId,
            int maxRetries,
            int retryDelaySeconds) {
    }

    public record OrphanedJob(
            Long id,
            int maxRetries,
            int retryDelaySeconds) {
    }

    public record AvailableWorker(
            Long workerId,
            int availableSlots) {
    }
}
