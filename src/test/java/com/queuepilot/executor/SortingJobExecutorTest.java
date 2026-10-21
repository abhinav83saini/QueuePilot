package com.queuepilot.executor;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortingJobExecutorTest {

    private final SortingJobExecutor executor = new SortingJobExecutor();

    private JobExecutionResult run(String json) {
        return executor.execute(JsonSupport.parse(json));
    }

    private String errorFor(String json) {
        return assertThrows(InvalidJobInputException.class, () -> run(json))
                .getMessage();
    }

    @Test
    void sortsIntegersAscendingByDefault() {
        JobExecutionResult result = run("{\"values\":[5,2,4]}");

        assertEquals(List.of(2L, 4L, 5L), result.output().get("sortedValues"));
        assertEquals(3, result.output().get("count"));
        assertEquals("{\"sortedValues\":[2,4,5],\"count\":3}", result.toJson());
    }

    @Test
    void sortsDescendingWhenRequested() {
        JobExecutionResult result =
                run("{\"values\":[5,2,4],\"order\":\"desc\"}");

        assertEquals(List.of(5L, 4L, 2L), result.output().get("sortedValues"));
    }

    @Test
    void sortsNegativesAndDecimalsNumerically() {
        JobExecutionResult result = run("{\"values\":[10,-3,2.5,0,-3.25]}");

        assertEquals(
                List.of(
                        new java.math.BigDecimal("-3.25"),
                        -3L,
                        0L,
                        new java.math.BigDecimal("2.5"),
                        10L),
                result.output().get("sortedValues"));
    }

    @Test
    void reportsWholeNumbersWithoutFractionAndKeepsDuplicates() {
        JobExecutionResult result = run("{\"values\":[2.0,2,1]}");

        assertEquals(List.of(1L, 2L, 2L), result.output().get("sortedValues"));
        assertEquals(3, result.output().get("count"));
    }

    @Test
    void handlesNumbersBeyondLongRange() {
        JobExecutionResult result =
                run("{\"values\":[99999999999999999999,1]}");

        assertEquals(
                List.of(1L, new BigInteger("99999999999999999999")),
                result.output().get("sortedValues"));
    }

    @Test
    void emptyListIsValidAndReturnsEmptyResult() {
        JobExecutionResult result = run("{\"values\":[]}");

        assertEquals(List.of(), result.output().get("sortedValues"));
        assertEquals(0, result.output().get("count"));
        assertEquals("{\"sortedValues\":[],\"count\":0}", result.toJson());
    }

    @Test
    void rejectsMissingValues() {
        assertTrue(errorFor("{}").contains("'values' is required"));
        assertTrue(errorFor("{\"values\":null}").contains("'values' is required"));
    }

    @Test
    void rejectsValuesThatAreNotAnArray() {
        assertTrue(errorFor("{\"values\":\"5,2,4\"}")
                .contains("must be an array"));
        assertTrue(errorFor("{\"values\":5}").contains("must be an array"));
        assertTrue(errorFor("{\"values\":{\"a\":1}}")
                .contains("must be an array"));
    }

    @Test
    void rejectsNonNumericElementsAndNamesTheIndex() {
        assertTrue(errorFor("{\"values\":[1,\"two\",3]}")
                .contains("values[1] must be a number"));
        assertTrue(errorFor("{\"values\":[1,2,null]}")
                .contains("values[2] must be a number"));
        assertTrue(errorFor("{\"values\":[true]}")
                .contains("values[0] must be a number"));
        assertTrue(errorFor("{\"values\":[[1,2]]}")
                .contains("values[0] must be a number"));
    }

    @Test
    void rejectsInputThatIsNotAnObject() {
        assertTrue(errorFor("[5,2,4]").contains("must be a JSON object"));
        assertTrue(errorFor("\"text\"").contains("must be a JSON object"));
        assertTrue(errorFor("null").contains("must be a JSON object"));
    }

    @Test
    void rejectsUnknownOrder() {
        assertTrue(errorFor("{\"values\":[1],\"order\":\"sideways\"}")
                .contains("'order'"));
        assertTrue(errorFor("{\"values\":[1],\"order\":5}")
                .contains("'order'"));
    }

    @Test
    void rejectsMoreThanTheMaximumNumberOfValues() {
        StringBuilder json = new StringBuilder("{\"values\":[");
        for (int i = 0; i <= SortingJobExecutor.MAX_VALUES; i++) {
            json.append(i == 0 ? "" : ",").append(i);
        }
        json.append("]}");

        assertTrue(errorFor(json.toString()).contains("at most "
                + SortingJobExecutor.MAX_VALUES));
    }

    @Test
    void acceptsExactlyTheMaximumNumberOfValues() {
        StringBuilder json = new StringBuilder("{\"values\":[");
        for (int i = 0; i < SortingJobExecutor.MAX_VALUES; i++) {
            json.append(i == 0 ? "" : ",").append(SortingJobExecutor.MAX_VALUES - i);
        }
        json.append("]}");

        assertEquals(SortingJobExecutor.MAX_VALUES,
                run(json.toString()).output().get("count"));
    }

    @Test
    void rejectsNumbersWithTooManyDigits() {
        String tooManyDigits = "1" + "0".repeat(100) + "1"; // 102 digits

        assertEquals("values[1] is outside the supported numeric range",
                errorFor("{\"values\":[1," + tooManyDigits + "]}"));
    }
}
