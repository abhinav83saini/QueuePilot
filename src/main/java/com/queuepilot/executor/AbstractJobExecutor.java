package com.queuepilot.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Abstract base class providing common execution lifecycle, logging, and error handling.
 *
 * <p>Demonstrates core Object-Oriented Programming (OOP) concepts:
 * <ul>
 *   <li><b>Abstraction & Interfaces:</b> Implements the {@link JobExecutor} interface.</li>
 *   <li><b>Inheritance:</b> Concrete task types extend this base class.</li>
 *   <li><b>Polymorphism:</b> Dispatches execution to subclasses via {@link #doExecute(JsonNode)}.</li>
 *   <li><b>Template Method Pattern:</b> Enforces input validation before delegating execution.</li>
 * </ul>
 */
public abstract class AbstractJobExecutor implements JobExecutor {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public JobExecutionResult execute(JsonNode input) {
        // Template step 1: Validate input according to subclass specifications
        validateInput(input);

        long start = System.currentTimeMillis();
        try {
            // Template step 2: Polymorphic execution delegated to subclass
            JobExecutionResult result = doExecute(input);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Task completed by {} in {}ms", getClass().getSimpleName(), elapsed);
            return result;
        } catch (JobExecutionException jee) {
            throw jee;
        } catch (Exception ex) {
            throw new JobExecutionException(
                    "Execution error in " + getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Subclasses implement this method with their specific task computation logic.
     *
     * @param input the validated JSON input payload
     * @return the execution result containing output data
     */
    protected abstract JobExecutionResult doExecute(JsonNode input);
}
