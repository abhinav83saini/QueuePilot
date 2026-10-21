package com.queuepilot.executor;

/**
 * Base type for failures raised while running a built-in job executor.
 *
 * <p>The message is persisted as the job's error message and may be shown to
 * API clients, so it must describe the problem without exposing internals
 * (stack traces, credentials, other jobs' data).</p>
 */
public class JobExecutionException extends RuntimeException {

    public JobExecutionException(String message) {
        super(message);
    }

    public JobExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
