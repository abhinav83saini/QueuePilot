package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Simulated email job, moved out of the scheduler with identical behaviour.
 *
 * <p><b>This never sends an email</b>: it has no mail dependency, no network
 * access and no credentials. It only reports that the simulation completed.</p>
 *
 * <p><b>Job type:</b> {@code EMAIL}</p>
 */
@Component
public class SimulatedEmailJobExecutor extends AbstractJobExecutor {

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("EMAIL");
    }

    @Override
    public void validateInput(JsonNode input) {
        JobInputs.requireObject(input);
    }

    @Override
    protected JobExecutionResult doExecute(JsonNode input) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("simulated", true);
        output.put("message", "Demo email completed; no email was sent.");

        return JobExecutionResult.of(output);
    }
}
