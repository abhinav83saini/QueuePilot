package com.queuepilot.executor;

/** The job's input data is missing, malformed or outside the allowed bounds. */
public class InvalidJobInputException extends JobExecutionException {

    public InvalidJobInputException(String message) {
        super(message);
    }
}
