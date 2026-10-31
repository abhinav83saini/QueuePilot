package com.queuepilot.controller;

import com.queuepilot.dto.BenchmarkComparisonResult;
import com.queuepilot.dto.BenchmarkMetrics;
import com.queuepilot.service.BenchmarkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    /**
     * Returns historical execution and scheduling metrics from the system (SRS F15, 4.1).
     */
    @GetMapping("/metrics")
    public BenchmarkMetrics getMetrics() {
        return benchmarkService.getSystemMetrics();
    }

    /**
     * Executes a policy benchmark comparison (FCFS vs. PRIORITY_AGING vs. SJF) (SRS F15, U2).
     */
    @PostMapping("/compare")
    public BenchmarkComparisonResult comparePolicies(
            @RequestParam(defaultValue = "15") int jobCount,
            @RequestParam(defaultValue = "2") int workerCount,
            @RequestParam(defaultValue = "10.0") double agingIntervalSeconds) {

        return benchmarkService.runPolicyComparison(jobCount, workerCount, agingIntervalSeconds);
    }

    /**
     * Initiates a controlled worker failure simulation (SRS F15, U2, U5).
     */
    @PostMapping("/simulate-worker-failure")
    public ResponseEntity<Map<String, Object>> simulateWorkerFailure(
            @RequestParam(required = false) Long workerId) {

        Map<String, Object> result = benchmarkService.simulateWorkerFailure(workerId);
        boolean success = Boolean.TRUE.equals(result.get("success"));

        return success ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    /**
     * Recovers simulated failed workers back to HEALTHY (SRS U5).
     */
    @PostMapping("/recover-workers")
    public ResponseEntity<Map<String, Object>> recoverWorkers(
            @RequestParam(required = false) Long workerId) {

        return ResponseEntity.ok(benchmarkService.recoverWorkers(workerId));
    }
}
