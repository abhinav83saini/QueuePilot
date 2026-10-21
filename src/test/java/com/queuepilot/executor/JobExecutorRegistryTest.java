package com.queuepilot.executor;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobExecutorRegistryTest {

    private final SortingJobExecutor sorting = new SortingJobExecutor();
    private final TextAnalysisJobExecutor text = new TextAnalysisJobExecutor();
    private final MathJobExecutor math = new MathJobExecutor();
    private final DemoJobExecutor demo = new DemoJobExecutor();
    private final SimulatedEmailJobExecutor email = new SimulatedEmailJobExecutor();

    private JobExecutorRegistry registry() {
        return new JobExecutorRegistry(
                List.of(sorting, text, math, demo, email));
    }

    @Test
    void findsEveryBuiltInJobType() {
        JobExecutorRegistry registry = registry();

        assertSame(sorting, registry.find("SORT").orElseThrow());
        assertSame(sorting, registry.find("SORTING").orElseThrow());
        assertSame(text, registry.find("TEXT_ANALYSIS").orElseThrow());
        assertSame(math, registry.find("MATH").orElseThrow());
        assertSame(demo, registry.find("DEMO").orElseThrow());
        assertSame(demo, registry.find("NOOP").orElseThrow());
        assertSame(email, registry.find("EMAIL").orElseThrow());
    }

    @Test
    void lookupIgnoresCaseAndSurroundingWhitespace() {
        JobExecutorRegistry registry = registry();

        assertSame(math, registry.find("math").orElseThrow());
        assertSame(math, registry.find("  Math ").orElseThrow());
    }

    @Test
    void unknownBlankAndNullTypesAreNotFound() {
        JobExecutorRegistry registry = registry();

        assertEquals(Optional.empty(), registry.find("SHELL"));
        assertEquals(Optional.empty(), registry.find(""));
        assertEquals(Optional.empty(), registry.find("   "));
        assertEquals(Optional.empty(), registry.find(null));
    }

    @Test
    void listsSupportedTypesAlphabetically() {
        assertEquals(
                List.of("DEMO", "EMAIL", "MATH", "NOOP", "SORT", "SORTING",
                        "TEXT_ANALYSIS"),
                List.copyOf(registry().supportedJobTypes()));
    }

    @Test
    void executingAnUnsupportedTypeFailsWithAUsefulMessage() {
        UnsupportedJobTypeException error = assertThrows(
                UnsupportedJobTypeException.class,
                () -> registry().execute("SHELL", JsonSupport.parse("{}")));

        assertTrue(error.getMessage().startsWith("Unsupported job type: SHELL"));
        assertTrue(error.getMessage().contains("MATH"));
        assertTrue(error.getMessage().contains("SORT"));
    }

    @Test
    void executingANullTypeFailsAsUnsupported() {
        assertThrows(UnsupportedJobTypeException.class,
                () -> registry().execute(null, JsonSupport.parse("{}")));
    }

    @Test
    void executeRunsTheMatchingExecutor() {
        JobExecutionResult result = registry().execute(
                "sort", JsonSupport.parse("{\"values\":[3,1,2]}"));

        assertEquals("{\"sortedValues\":[1,2,3],\"count\":3}", result.toJson());
    }

    @Test
    void executorInputErrorsPropagateUnchanged() {
        InvalidJobInputException error = assertThrows(
                InvalidJobInputException.class,
                () -> registry().execute(
                        "MATH",
                        JsonSupport.parse(
                                "{\"operation\":\"DIVIDE\",\"a\":1,\"b\":0}")));

        assertEquals("Division by zero is not allowed", error.getMessage());
    }

    @Test
    void demoAndNoopKeepTheirOriginalOutputForAnyInput() {
        String expected = "{\"success\":true,\"message\":\"Demo job completed.\"}";

        assertEquals(expected, registry().execute(
                "DEMO", JsonSupport.parse("{\"demo\":true}")).toJson());
        assertEquals(expected, registry().execute(
                "NOOP", JsonSupport.parse("[1,2,3]")).toJson());
    }

    @Test
    void emailStaysSimulated() {
        JobExecutionResult result = registry().execute(
                "EMAIL", JsonSupport.parse("{\"to\":\"someone@example.com\"}"));

        assertEquals(
                "{\"simulated\":true,"
                        + "\"message\":\"Demo email completed; no email was sent.\"}",
                result.toJson());
    }

    @Test
    void unexpectedRuntimeErrorsAreWrappedWithASafeMessage() {
        JobExecutor broken = new JobExecutor() {
            @Override
            public Set<String> supportedJobTypes() {
                return Set.of("BROKEN");
            }

            @Override
            public JobExecutionResult execute(JsonNode input) {
                throw new IllegalStateException("secret internal detail");
            }
        };

        JobExecutionException error = assertThrows(
                JobExecutionException.class,
                () -> new JobExecutorRegistry(List.of(broken))
                        .execute("broken", JsonSupport.parse("{}")));

        assertEquals("Job executor for type BROKEN failed unexpectedly",
                error.getMessage());
        assertFalse(error.getMessage().contains("secret"));
        assertNotNull(error.getCause());
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void rejectsTwoExecutorsClaimingTheSameType() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new JobExecutorRegistry(
                        List.of(demo, new DemoJobExecutor())));

        assertTrue(error.getMessage().contains("DEMO"));
    }

    @Test
    void rejectsAnExecutorThatDeclaresABlankType() {
        JobExecutor blank = new JobExecutor() {
            @Override
            public Set<String> supportedJobTypes() {
                return Set.of("  ");
            }

            @Override
            public JobExecutionResult execute(JsonNode input) {
                return JobExecutionResult.of(java.util.Map.of());
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new JobExecutorRegistry(List.of(blank)));
    }

    @Test
    void invalidJsonTextIsRejectedWithoutLeakingParserDetails() {
        InvalidJobInputException error = assertThrows(
                InvalidJobInputException.class,
                () -> JsonSupport.parse("{not json"));

        assertEquals("Job input is not valid JSON", error.getMessage());
    }
}
