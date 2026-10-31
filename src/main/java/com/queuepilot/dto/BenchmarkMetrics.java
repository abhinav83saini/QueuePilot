package com.queuepilot.dto;

import java.util.Map;

public record BenchmarkMetrics(
        long totalJobs,
        long completedJobs,
        long failedJobs,
        long runningJobs,
        long queuedJobs,
        double averageWaitingTimeMs,
        double averageTurnaroundTimeMs,
        double throughputJobsPerMinute,
        double workerUtilizationPercent,
        Map<String, Long> statusCounts
) {}
