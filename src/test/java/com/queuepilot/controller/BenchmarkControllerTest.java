package com.queuepilot.controller;

import com.queuepilot.dto.BenchmarkComparisonResult;
import com.queuepilot.dto.BenchmarkMetrics;
import com.queuepilot.service.BenchmarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BenchmarkControllerTest {

    @Mock
    private BenchmarkService benchmarkService;

    private BenchmarkController controller;

    @BeforeEach
    void setUp() {
        controller = new BenchmarkController(benchmarkService);
    }

    @Test
    void getMetricsReturnsBenchmarkMetrics() {
        BenchmarkMetrics metrics = new BenchmarkMetrics(
                100, 80, 5, 5, 10, 150.0, 600.0, 12.5, 78.4, Map.of("COMPLETED", 80L)
        );

        when(benchmarkService.getSystemMetrics()).thenReturn(metrics);

        BenchmarkMetrics result = controller.getMetrics();
        assertNotNull(result);
        assertEquals(100, result.totalJobs());
        assertEquals(80, result.completedJobs());
        assertEquals(150.0, result.averageWaitingTimeMs());
    }

    @Test
    void comparePoliciesDelegatesToService() {
        BenchmarkComparisonResult comparison = new BenchmarkComparisonResult(
                15, 2, 10.0, OffsetDateTime.now(), List.of()
        );

        when(benchmarkService.runPolicyComparison(15, 2, 10.0)).thenReturn(comparison);

        BenchmarkComparisonResult result = controller.comparePolicies(15, 2, 10.0);
        assertNotNull(result);
        assertEquals(15, result.jobCount());
        assertEquals(2, result.workerCount());
    }

    @Test
    void simulateWorkerFailureReturnsSuccessResponse() {
        Map<String, Object> serviceResult = Map.of(
                "success", true,
                "simulatedWorkerId", 3L,
                "affectedJobs", List.of(45L),
                "message", "Simulated failure for worker 3"
        );

        when(benchmarkService.simulateWorkerFailure(3L)).thenReturn(serviceResult);

        ResponseEntity<Map<String, Object>> response = controller.simulateWorkerFailure(3L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(3L, response.getBody().get("simulatedWorkerId"));
    }

    @Test
    void simulateWorkerFailureReturnsBadRequestWhenNoWorkerAvailable() {
        Map<String, Object> serviceResult = Map.of(
                "success", false,
                "message", "No active worker"
        );

        when(benchmarkService.simulateWorkerFailure(null)).thenReturn(serviceResult);

        ResponseEntity<Map<String, Object>> response = controller.simulateWorkerFailure(null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void recoverWorkersReturnsSuccessResponse() {
        Map<String, Object> serviceResult = Map.of(
                "success", true,
                "recoveredCount", 3,
                "message", "Recovered 3 workers"
        );

        when(benchmarkService.recoverWorkers(null)).thenReturn(serviceResult);

        ResponseEntity<Map<String, Object>> response = controller.recoverWorkers(null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().get("recoveredCount"));
    }
}
