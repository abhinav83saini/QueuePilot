package com.queuepilot.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The structured output of a successful job execution.
 *
 * <p>The output is a plain, ordered key/value structure (values are
 * strings, booleans, numbers, lists or nested maps) so executors and their
 * tests stay independent of any JSON library. {@link #toJson()} produces the
 * text that the scheduler stores in the {@code output_data} JSONB column.</p>
 */
public record JobExecutionResult(Map<String, Object> output) {

    public JobExecutionResult {
        Objects.requireNonNull(output, "output");
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static JobExecutionResult of(Map<String, Object> output) {
        return new JobExecutionResult(output);
    }

    /** The output serialised as a compact JSON object. */
    public String toJson() {
        return JsonSupport.toJson(output);
    }
}
