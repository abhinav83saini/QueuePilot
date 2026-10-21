package com.queuepilot.executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only place in the executor package that talks to Jackson.
 *
 * <p>QueuePilot runs on Spring Boot 4, which uses Jackson 3
 * ({@code tools.jackson.*}), the same package {@code Job} and
 * {@code JobController} already import. Keeping the Jackson calls here means
 * the executors themselves work on plain Java values.</p>
 */
public final class JsonSupport {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonSupport() {
    }

    /** Parses JSON text; {@code null} is treated as the JSON value null. */
    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json == null ? "null" : json);
        } catch (RuntimeException ex) {
            throw new InvalidJobInputException("Job input is not valid JSON");
        }
    }

    /**
     * Converts a JSON tree to plain Java values: {@code Map}, {@code List},
     * {@code String}, {@code Boolean}, {@code Number} or {@code null}.
     */
    public static Object toJava(JsonNode node) {
        if (node == null) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    /** Serialises plain Java values (maps, lists, numbers, ...) as JSON text. */
    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
