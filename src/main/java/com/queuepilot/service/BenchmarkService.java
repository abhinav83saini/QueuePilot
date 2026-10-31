package com.queuepilot.service;

import com.queuepilot.dto.BenchmarkComparisonResult;
import com.queuepilot.dto.BenchmarkMetrics;
import com.queuepilot.dto.PolicyBenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final JdbcTemplate jdbc;

    public BenchmarkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Calculates system metrics (waiting time, turnaround time, throughput, worker utilization)
     * from historical execution records in the database (SRS F15, 4.1).
     */
    public BenchmarkMetrics getSystemMetrics() {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS cnt FROM jobs GROUP BY status", rs -> {
            while (rs.next()) {
                statusCounts.put(rs.getString("status"), rs.getLong("cnt"));
            }
            return null;
        });

        long totalJobs = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long completedJobs = statusCounts.getOrDefault("COMPLETED", 0L);
        long failedJobs = statusCounts.getOrDefault("FAILED", 0L);
        long runningJobs = statusCounts.getOrDefault("RUNNING", 0L);
        long queuedJobs = statusCounts.getOrDefault("QUEUED", 0L);

        Double avgWaitingTimeMs = jdbc.queryForObject("""
                SELECT AVG(EXTRACT(EPOCH FROM (started_at - created_at)) * 1000)
                FROM jobs
                WHERE started_at IS NOT NULL
                """, Double.class);

        Double avgTurnaroundTimeMs = jdbc.queryForObject("""
                SELECT AVG(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)
                FROM jobs
                WHERE status = 'COMPLETED' AND completed_at IS NOT NULL
                """, Double.class);

        // Throughput calculation: completed jobs divided by total time span (in minutes)
        Double spanMinutes = jdbc.queryForObject("""
                SELECT GREATEST(1.0, EXTRACT(EPOCH FROM (COALESCE(MAX(completed_at), NOW()) - MIN(created_at))) / 60.0)
                FROM jobs
                WHERE status = 'COMPLETED'
                """, Double.class);

        double throughputPerMinute = (spanMinutes != null && spanMinutes > 0 && completedJobs > 0)
                ? (completedJobs / spanMinutes)
                : 0.0;

        // Worker utilization: total busy worker time vs total available worker time
        Double totalBusySeconds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(finished_at, NOW()) - started_at))), 0.0)
                FROM execution_attempts
                WHERE started_at IS NOT NULL
                """, Double.class);

        Integer activeWorkerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workers WHERE health_status <> 'OFFLINE'", Integer.class);

        double workerUtilization = 0.0;
        if (totalBusySeconds != null && totalBusySeconds > 0
                && activeWorkerCount != null && activeWorkerCount > 0
                && spanMinutes != null && spanMinutes > 0) {
            double totalCapacitySeconds = spanMinutes * 60.0 * activeWorkerCount;
            workerUtilization = Math.min(100.0, (totalBusySeconds / totalCapacitySeconds) * 100.0);
        }

        return new BenchmarkMetrics(
                totalJobs,
                completedJobs,
                failedJobs,
                runningJobs,
                queuedJobs,
                avgWaitingTimeMs != null ? Math.round(avgWaitingTimeMs * 100.0) / 100.0 : 0.0,
                avgTurnaroundTimeMs != null ? Math.round(avgTurnaroundTimeMs * 100.0) / 100.0 : 0.0,
                Math.round(throughputPerMinute * 100.0) / 100.0,
                Math.round(workerUtilization * 10.0) / 10.0,
                statusCounts
        );
    }

    /**
     * Executes a repeatable benchmark simulation comparing FCFS, PRIORITY_AGING, and SJF
     * on the identical workload (SRS F15, U2, 4.1).
     */
    public BenchmarkComparisonResult runPolicyComparison(
            int jobCount, int workerCount, double agingIntervalSeconds) {

        int validJobCount = Math.max(5, Math.min(jobCount, 100));
        int validWorkers = Math.max(1, Math.min(workerCount, 16));
        double validAgingInterval = agingIntervalSeconds > 0 ? agingIntervalSeconds : 10.0;

        List<SimulatedJobSpec> workload = generateDeterministicWorkload(validJobCount);

        List<PolicyBenchmarkResult> results = new ArrayList<>();
        results.add(simulatePolicy("FCFS", workload, validWorkers, validAgingInterval));
        results.add(simulatePolicy("PRIORITY_AGING", workload, validWorkers, validAgingInterval));
        results.add(simulatePolicy("SJF", workload, validWorkers, validAgingInterval));

        return new BenchmarkComparisonResult(
                validJobCount,
                validWorkers,
                validAgingInterval,
                OffsetDateTime.now(),
                results
        );
    }

    private record SimulatedJobSpec(
            int id,
            String type,
            int priority,
            long durationMs,
            long arrivalTimeMs
    ) {}

    private List<SimulatedJobSpec> generateDeterministicWorkload(int count) {
        List<SimulatedJobSpec> list = new ArrayList<>(count);
        String[] types = {"MATH", "SORT", "TEXT_ANALYSIS"};

        for (int i = 1; i <= count; i++) {
            int priority = 1 + ((i * 7) % 10); // deterministic priorities 1 to 10
            long durationMs = 60 + ((i * 137L) % 700); // durations between 60ms and 760ms
            long arrivalTimeMs = (i - 1) * 30L; // arrivals staggered by 30ms
            String type = types[i % types.length];

            list.add(new SimulatedJobSpec(i, type, priority, durationMs, arrivalTimeMs));
        }

        return list;
    }

    private PolicyBenchmarkResult simulatePolicy(
            String policy, List<SimulatedJobSpec> workload, int workerCount, double agingIntervalSeconds) {

        long[] workerNextFreeTime = new long[workerCount];
        List<SimulatedJobSpec> uncompleted = new ArrayList<>(workload);
        List<PolicyBenchmarkResult.JobTrace> trace = new ArrayList<>(workload.size());

        long currentTime = 0;

        while (!uncompleted.isEmpty()) {
            // Find earliest worker free time
            long earliestFreeTime = Long.MAX_VALUE;
            int freeWorkerIndex = 0;

            for (int w = 0; w < workerCount; w++) {
                if (workerNextFreeTime[w] < earliestFreeTime) {
                    earliestFreeTime = workerNextFreeTime[w];
                    freeWorkerIndex = w;
                }
            }

            currentTime = Math.max(currentTime, earliestFreeTime);

            // Filter jobs that have arrived by currentTime
            final long simTime = currentTime;
            List<SimulatedJobSpec> arrived = uncompleted.stream()
                    .filter(j -> j.arrivalTimeMs() <= simTime)
                    .toList();

            if (arrived.isEmpty()) {
                // Advance clock to next earliest job arrival
                currentTime = uncompleted.stream()
                        .mapToLong(SimulatedJobSpec::arrivalTimeMs)
                        .min()
                        .orElse(currentTime);
                continue;
            }

            // Select job based on policy
            SimulatedJobSpec selectedJob;

            switch (policy) {
                case "SJF" -> selectedJob = arrived.stream()
                        .min(Comparator.comparingLong(SimulatedJobSpec::durationMs)
                                .thenComparingLong(SimulatedJobSpec::arrivalTimeMs))
                        .orElseThrow();

                case "PRIORITY_AGING" -> selectedJob = arrived.stream()
                        .max(Comparator.comparingDouble((SimulatedJobSpec j) -> {
                            double waitSec = (simTime - j.arrivalTimeMs()) / 1000.0;
                            return j.priority() + (waitSec / agingIntervalSeconds);
                        }).thenComparing((j1, j2) -> Long.compare(j2.arrivalTimeMs(), j1.arrivalTimeMs())))
                        .orElseThrow();

                default -> // FCFS
                        selectedJob = arrived.stream()
                        .min(Comparator.comparingLong(SimulatedJobSpec::arrivalTimeMs)
                                .thenComparingInt(SimulatedJobSpec::id))
                        .orElseThrow();
            }

            uncompleted.remove(selectedJob);

            long startTime = Math.max(currentTime, workerNextFreeTime[freeWorkerIndex]);
            long completionTime = startTime + selectedJob.durationMs();
            workerNextFreeTime[freeWorkerIndex] = completionTime;

            long waitingTime = startTime - selectedJob.arrivalTimeMs();
            long turnaroundTime = completionTime - selectedJob.arrivalTimeMs();

            trace.add(new PolicyBenchmarkResult.JobTrace(
                    selectedJob.id(),
                    selectedJob.type(),
                    selectedJob.priority(),
                    selectedJob.durationMs(),
                    selectedJob.arrivalTimeMs(),
                    startTime,
                    completionTime,
                    waitingTime,
                    turnaroundTime,
                    freeWorkerIndex + 1
            ));
        }

        long totalDurationMs = trace.stream()
                .mapToLong(PolicyBenchmarkResult.JobTrace::completionTimeMs)
                .max()
                .orElse(0L);

        double avgWaitingTime = trace.stream()
                .mapToLong(PolicyBenchmarkResult.JobTrace::waitingTimeMs)
                .average()
                .orElse(0.0);

        double avgTurnaroundTime = trace.stream()
                .mapToLong(PolicyBenchmarkResult.JobTrace::turnaroundTimeMs)
                .average()
                .orElse(0.0);

        double throughput = totalDurationMs > 0
                ? (trace.size() * 1000.0) / totalDurationMs
                : 0.0;

        long totalBusyTime = trace.stream()
                .mapToLong(PolicyBenchmarkResult.JobTrace::durationMs)
                .sum();

        double workerUtilization = totalDurationMs > 0
                ? Math.min(100.0, (totalBusyTime * 100.0) / (totalDurationMs * workerCount))
                : 0.0;

        return new PolicyBenchmarkResult(
                policy,
                trace.size(),
                workerCount,
                totalDurationMs,
                Math.round(avgWaitingTime * 100.0) / 100.0,
                Math.round(avgTurnaroundTime * 100.0) / 100.0,
                Math.round(throughput * 100.0) / 100.0,
                Math.round(workerUtilization * 10.0) / 10.0,
                trace
        );
    }

    /**
     * Simulates worker failure (SRS F15, U2, U5).
     * Marks the worker UNHEALTHY and expires its active attempt lease, triggering
     * immediate automatic reassignment by the recovery manager.
     */
    public Map<String, Object> simulateWorkerFailure(Long requestedWorkerId) {
        Long workerId = requestedWorkerId;

        if (workerId == null) {
            // Pick a healthy worker that has an active running job, or any healthy worker
            List<Long> busyWorkers = jdbc.queryForList("""
                    SELECT ea.worker_id
                    FROM execution_attempts ea
                    JOIN workers w ON w.worker_id = ea.worker_id
                    WHERE ea.status = 'RUNNING' AND w.health_status = 'HEALTHY'
                    LIMIT 1
                    """, Long.class);

            if (!busyWorkers.isEmpty()) {
                workerId = busyWorkers.get(0);
            } else {
                List<Long> healthyWorkers = jdbc.queryForList("""
                        SELECT worker_id FROM workers WHERE health_status = 'HEALTHY' LIMIT 1
                        """, Long.class);
                if (!healthyWorkers.isEmpty()) {
                    workerId = healthyWorkers.get(0);
                }
            }
        }

        if (workerId == null) {
            return Map.of(
                    "success", false,
                    "message", "No active or healthy worker found to simulate failure."
            );
        }

        // 1. Mark worker UNHEALTHY and backdate heartbeat
        jdbc.update("""
                UPDATE workers
                SET health_status = 'UNHEALTHY',
                    last_heartbeat = NOW() - INTERVAL '60 seconds'
                WHERE worker_id = ?
                """, workerId);

        // 2. Expire active attempt lease on this worker
        List<Long> affectedJobs = jdbc.queryForList("""
                UPDATE execution_attempts
                SET lease_expires_at = NOW() - INTERVAL '5 seconds'
                WHERE worker_id = ? AND status = 'RUNNING'
                RETURNING job_id
                """, Long.class, workerId);

        for (Long jobId : affectedJobs) {
            jdbc.update("""
                    INSERT INTO job_events (job_id, event_type, details)
                    VALUES (?, 'WORKER_FAILURE_SIMULATED', CAST(? AS jsonb))
                    """, jobId, "{\"simulatedWorkerId\":" + workerId + "}");
        }

        log.warn("Simulated failure for worker {}. Lease expired on {} active job(s).",
                workerId, affectedJobs.size());

        return Map.of(
                "success", true,
                "simulatedWorkerId", workerId,
                "affectedJobs", affectedJobs,
                "message", "Simulated failure for worker " + workerId
                        + ". Lease expired on " + affectedJobs.size()
                        + " active job(s); recovery manager will detect and requeue work automatically."
        );
    }

    /**
     * Recovers simulated failed workers back to HEALTHY status (SRS U5).
     */
    public Map<String, Object> recoverWorkers(Long workerId) {
        int updated;

        if (workerId != null) {
            updated = jdbc.update("""
                    UPDATE workers
                    SET health_status = 'HEALTHY', last_heartbeat = NOW()
                    WHERE worker_id = ?
                    """, workerId);
        } else {
            updated = jdbc.update("""
                    UPDATE workers
                    SET health_status = 'HEALTHY', last_heartbeat = NOW()
                    WHERE health_status <> 'HEALTHY'
                    """);
        }

        return Map.of(
                "success", true,
                "recoveredCount", updated,
                "message", "Recovered " + updated + " worker(s) to HEALTHY status."
        );
    }
}
