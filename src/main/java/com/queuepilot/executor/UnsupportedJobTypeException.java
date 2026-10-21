package com.queuepilot.executor;

/** No registered executor handles the requested job type. */
public class UnsupportedJobTypeException extends JobExecutionException {

    public UnsupportedJobTypeException(String message) {
        super(message);
    }
}
