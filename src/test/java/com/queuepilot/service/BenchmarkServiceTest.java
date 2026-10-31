package com.queuepilot.service;

import com.queuepilot.dto.BenchmarkComparisonResult;
import com.queuepilot.dto.BenchmarkMetrics;
import com.queuepilot.dto.PolicyBenchmarkResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BenchmarkServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private BenchmarkService benchmarkService;

    @BeforeEach
    void setUp() {
        benchmarkService = new BenchmarkService(jdbc);
    }

    @Test
    void policyComparisonReturnsResultsForAllThreePolicies() {
        BenchmarkComparisonResult result = benchmarkService.runPolicyComparison(15, 2, 10.0);

        assertNotNull(result);
        assertEquals(15, result.jobCount());
        assertEquals(2, result.workerCount());
        assertEquals(10.0, result.priorityAgingIntervalSeconds());
        assertEquals(3, result.policyResults().size());

        List<String> policyNames = result.policyResults().stream()
                .map(PolicyBenchmarkResult::policy)
                .toList();

        assertTrue(policyNames.contains("FCFS"));
        assertTrue(policyNames.contains("PRIORITY_AGING"));
        assertTrue(policyNames.contains("SJF"));

        for (PolicyBenchmarkResult pr : result.policyResults()) {
            assertEquals(15, pr.totalJobs());
            assertEquals(2, pr.workerCount());
            assertTrue(pr.totalDurationMs() > 0);
            assertTrue(pr.averageWaitingTimeMs() >= 0);
            assertTrue(pr.averageTurnaroundTimeMs() > 0);
            assertTrue(pr.throughputJobsPerSecond() > 0);
            assertTrue(pr.workerUtilizationPercent() > 0 && pr.workerUtilizationPercent() <= 100.0);
            assertEquals(15, pr.executionTrace().size());
        }
    }

    @Test
    void policyComparisonIsDeterministicAcrossRepeatedRuns() {
        BenchmarkComparisonResult run1 = benchmarkService.runPolicyComparison(20, 3, 10.0);
        BenchmarkComparisonResult run2 = benchmarkService.runPolicyComparison(20, 3, 10.0);

        for (int i = 0; i < run1.policyResults().size(); i++) {
            PolicyBenchmarkResult p1 = run1.policyResults().get(i);
            PolicyBenchmarkResult p2 = run2.policyResults().get(i);

            assertEquals(p1.policy(), p2.policy());
            assertEquals(p1.totalDurationMs(), p2.totalDurationMs());
            assertEquals(p1.averageWaitingTimeMs(), p2.averageWaitingTimeMs());
            assertEquals(p1.averageTurnaroundTimeMs(), p2.averageTurnaroundTimeMs());
            assertEquals(p1.throughputJobsPerSecond(), p2.throughputJobsPerSecond());
            assertEquals(p1.workerUtilizationPercent(), p2.workerUtilizationPercent());
        }
    }

    @Test
    void simulatesWorkerFailureByExpiringLeaseAndMarkingUnhealthy() {
        when(jdbc.queryForList(contains("FROM execution_attempts ea"), eq(Long.class)))
                .thenReturn(List.of(5L));
        when(jdbc.update(contains("UPDATE workers\nSET health_status = 'UNHEALTHY'"), eq(5L)))
                .thenReturn(1);
        when(jdbc.queryForList(contains("UPDATE execution_attempts\nSET lease_expires_at"), eq(Long.class), eq(5L)))
                .thenReturn(List.of(101L));

        Map<String, Object> result = benchmarkService.simulateWorkerFailure(5L);

        assertTrue((Boolean) result.get("success"));
        assertEquals(5L, result.get("simulatedWorkerId"));
        assertEquals(List.of(101L), result.get("affectedJobs"));

        verify(jdbc).update(contains("UPDATE workers\nSET health_status = 'UNHEALTHY'"), eq(5L));
        verify(jdbc).update(contains("WORKER_FAILURE_SIMULATED"), eq(101L), contains("simulatedWorkerId"));
    }

    @Test
    void recoversWorkersSuccessfully() {
        when(jdbc.update(contains("UPDATE workers\nSET health_status = 'HEALTHY'"))).thenReturn(2);

        Map<String, Object> result = benchmarkService.recoverWorkers(null);

        assertTrue((Boolean) result.get("success"));
        assertEquals(2, result.get("recoveredCount"));
    }

    @Test
    void getsSystemMetricsFromHistoricalRecords() {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            // Simulating query callback
            return null;
        }).when(jdbc).query(contains("GROUP BY status"), any(RowCallbackHandler.class));

        when(jdbc.queryForObject(contains("SELECT AVG(EXTRACT(EPOCH FROM (started_at - created_at))"), eq(Double.class)))
                .thenReturn(250.0);
        when(jdbc.queryForObject(contains("SELECT AVG(EXTRACT(EPOCH FROM (completed_at - created_at))"), eq(Double.class)))
                .thenReturn(850.0);

        BenchmarkMetrics metrics = benchmarkService.getSystemMetrics();

        assertNotNull(metrics);
        assertEquals(250.0, metrics.averageWaitingTimeMs());
        assertEquals(850.0, metrics.averageTurnaroundTimeMs());
    }
}
