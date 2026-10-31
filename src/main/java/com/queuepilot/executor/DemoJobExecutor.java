package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The original demonstration job types, moved out of the scheduler with
 * identical behaviour: they accept any input and report success.
 *
 * <p><b>Job types:</b> {@code DEMO}, {@code NOOP}</p>
 */
@Component
public class DemoJobExecutor extends AbstractJobExecutor {

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("DEMO", "NOOP");
    }

    @Override
    public void validateInput(JsonNode input) {
        // Accepts any valid JSON input for demonstration purposes
    }

    @Override
    protected JobExecutionResult doExecute(JsonNode input) {
        if (input != null && (input.path("fail").asBoolean(false) || input.path("simulateFailure").asBoolean(false))) {
            throw new JobExecutionException("Simulated runtime failure (transient network timeout)");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("message", "Demo job completed.");

        return JobExecutionResult.of(output);
    }
}
