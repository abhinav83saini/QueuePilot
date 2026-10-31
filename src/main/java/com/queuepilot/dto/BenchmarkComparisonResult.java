package com.queuepilot.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record BenchmarkComparisonResult(
        int jobCount,
        int workerCount,
        double priorityAgingIntervalSeconds,
        OffsetDateTime executedAt,
        List<PolicyBenchmarkResult> policyResults
) {}
