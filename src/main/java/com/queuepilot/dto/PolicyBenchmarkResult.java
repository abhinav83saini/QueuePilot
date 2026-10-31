package com.queuepilot.dto;

import java.util.List;

public record PolicyBenchmarkResult(
        String policy,
        int totalJobs,
        int workerCount,
        long totalDurationMs,
        double averageWaitingTimeMs,
        double averageTurnaroundTimeMs,
        double throughputJobsPerSecond,
        double workerUtilizationPercent,
        List<JobTrace> executionTrace
) {
    public record JobTrace(
            int jobId,
            String jobType,
            int priority,
            long durationMs,
            long arrivalTimeMs,
            long startTimeMs,
            long completionTimeMs,
            long waitingTimeMs,
            long turnaroundTimeMs,
            int assignedWorker
    ) {}
}
