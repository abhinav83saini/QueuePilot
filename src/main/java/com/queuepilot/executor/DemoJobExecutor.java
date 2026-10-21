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
public class DemoJobExecutor implements JobExecutor {

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("DEMO", "NOOP");
    }

    @Override
    public JobExecutionResult execute(JsonNode input) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("message", "Demo job completed.");

        return JobExecutionResult.of(output);
    }
}
