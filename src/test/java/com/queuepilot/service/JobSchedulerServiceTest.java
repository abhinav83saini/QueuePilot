package com.queuepilot.service;

import com.queuepilot.executor.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobSchedulerServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private PlatformTransactionManager transactionManager;

    private JobExecutorRegistry executorRegistry;
    private JobSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        executorRegistry = new JobExecutorRegistry(List.of(
                new SortingJobExecutor(),
                new TextAnalysisJobExecutor(),
                new MathJobExecutor(),
                new DemoJobExecutor(),
                new SimulatedEmailJobExecutor()
        ));
    }

    @AfterEach
    void tearDown() {
        if (schedulerService != null) {
            schedulerService.unregisterWorkers();
        }
    }

    @Test
    void registersConfiguredNumberOfWorkersWithCapabilities() {
        AtomicLong workerCounter = new AtomicLong(100L);
        when(jdbc.queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1)))
                .thenAnswer(invocation -> workerCounter.getAndIncrement());

        schedulerService = new JobSchedulerService(jdbc, transactionManager, executorRegistry, 3, 1, 10.0);
        schedulerService.registerWorkers();

        assertEquals(3, schedulerService.getRegisteredWorkerIds().size());
        assertEquals(10.0, schedulerService.getPriorityAgingIntervalSeconds());

        // Each worker should be registered with all supported capabilities
        verify(jdbc, times(3)).queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1));
        verify(jdbc, atLeast(3 * executorRegistry.supportedJobTypes().size()))
                .update(contains("INSERT INTO worker_capabilities"), anyLong(), anyString());
    }

    @Test
    void unregistersWorkersByMarkingThemOffline() {
        AtomicLong workerCounter = new AtomicLong(200L);
        when(jdbc.queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1)))
                .thenAnswer(invocation -> workerCounter.getAndIncrement());

        schedulerService = new JobSchedulerService(jdbc, transactionManager, executorRegistry, 2, 1, 15.0);
        schedulerService.registerWorkers();

        schedulerService.unregisterWorkers();

        verify(jdbc).update(contains("SET health_status = 'OFFLINE'"), eq(200L));
        verify(jdbc).update(contains("SET health_status = 'OFFLINE'"), eq(201L));
    }

    @Test
    void pollQueueRefreshesHeartbeatsAndExtendsLeasesForHealthyWorkers() {
        when(jdbc.queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1)))
                .thenReturn(50L);

        schedulerService = new JobSchedulerService(jdbc, transactionManager, executorRegistry, 1, 1, 10.0);
        schedulerService.registerWorkers();

        // No expired leases, no orphaned jobs, no available workers
        when(jdbc.query(contains("FROM execution_attempts ea"), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.query(contains("FROM jobs j\nWHERE j.status = 'RUNNING'"), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.query(contains("FROM workers w"), any(RowMapper.class))).thenReturn(List.of());

        schedulerService.pollQueue();

        verify(jdbc).update(contains("UPDATE workers\nSET last_heartbeat = NOW()"), eq(50L));
        verify(jdbc).update(contains("UPDATE execution_attempts ea\nSET lease_expires_at = NOW() + INTERVAL '30 seconds'"));
    }

    @Test
    void pollQueueDispatchesAvailableJobsToWorkersMatchingCapabilities() {
        when(jdbc.queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1)))
                .thenReturn(10L);

        schedulerService = new JobSchedulerService(jdbc, transactionManager, executorRegistry, 1, 1, 10.0);
        schedulerService.registerWorkers();

        when(jdbc.query(contains("FROM execution_attempts ea"), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.query(contains("FROM jobs j\nWHERE j.status = 'RUNNING'"), any(RowMapper.class))).thenReturn(List.of());

        // 1 available worker with 1 slot
        JobSchedulerService.AvailableWorker worker = new JobSchedulerService.AvailableWorker(10L, 1);
        when(jdbc.query(contains("FROM workers w"), any(RowMapper.class))).thenReturn(List.of(worker));

        // 1 claimed job
        JobSchedulerService.ClaimedJob claimed = new JobSchedulerService.ClaimedJob(
                999L, "MATH", "{\"operation\":\"ADD\",\"a\":1,\"b\":1}", 3, 5);
        when(jdbc.query(contains("candidate AS"), any(RowMapper.class), eq(10L), eq(10.0)))
                .thenReturn(List.of(claimed));

        schedulerService.pollQueue();

        verify(jdbc).query(contains("candidate AS"), any(RowMapper.class), eq(10L), eq(10.0));
    }

    @Test
    void marksWorkerUnhealthyWhenLeaseExpires() {
        when(jdbc.queryForObject(contains("INSERT INTO workers"), eq(Long.class), anyString(), eq(1)))
                .thenReturn(77L);

        schedulerService = new JobSchedulerService(jdbc, transactionManager, executorRegistry, 1, 1, 10.0);
        schedulerService.registerWorkers();

        JobSchedulerService.ExpiredAttempt expired = new JobSchedulerService.ExpiredAttempt(
                55L, 77L, 100L, 3, 5);

        when(jdbc.query(contains("FROM execution_attempts ea"), any(RowMapper.class)))
                .thenReturn(List.of(expired));
        when(jdbc.query(contains("FROM jobs j\nWHERE j.status = 'RUNNING'"), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("FROM workers w"), any(RowMapper.class)))
                .thenReturn(List.of());

        schedulerService.pollQueue();

        verify(jdbc).update(contains("UPDATE workers\nSET health_status = 'UNHEALTHY'"), eq(77L));
    }
}
