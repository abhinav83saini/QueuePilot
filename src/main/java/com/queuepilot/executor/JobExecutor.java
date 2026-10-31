package com.queuepilot.executor;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * A built-in task that QueuePilot can run for a claimed job.
 *
 * <p>Implementations are stateless Spring beans that are discovered by
 * {@link JobExecutorRegistry}. They contain only the task logic: they know
 * nothing about scheduling, retries, leases, persistence or HTTP. Any
 * failure is reported by throwing a {@link JobExecutionException}; the
 * scheduler turns that into the existing failed-attempt / retry handling.</p>
 *
 * <p>Executors must be safe to repeat (the SRS does not promise exactly-once
 * execution), must not perform external side effects, and must never run
 * client-supplied code.</p>
 */
public interface JobExecutor {

    /**
     * The job types this executor handles. Matching is case-insensitive and
     * ignores surrounding whitespace; a type may be claimed by one executor
     * only.
     */
    Set<String> supportedJobTypes();

    /**
     * Validates the input data against this executor's expected schema and constraints.
     *
     * @param input the job's {@code input_data}
     * @throws InvalidJobInputException if the input is malformed, missing required fields,
     *         or exceeds bounds
     */
    default void validateInput(JsonNode input) {
        JobInputs.requireObject(input);
    }

    /**
     * Runs the task.
     *
     * @param input the job's {@code input_data} (never trusted)
     * @return the structured output that is stored as the job's JSON output
     * @throws JobExecutionException if the input is invalid or the task fails;
     *         the message is stored as the job's error message, so it must be
     *         safe to show to clients
     */
    JobExecutionResult execute(JsonNode input);
}
