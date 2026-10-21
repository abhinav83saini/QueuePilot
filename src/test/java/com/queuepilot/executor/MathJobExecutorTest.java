package com.queuepilot.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathJobExecutorTest {

    private final MathJobExecutor executor = new MathJobExecutor();

    private JobExecutionResult run(String json) {
        return executor.execute(JsonSupport.parse(json));
    }

    private Object resultOf(String operation, String a, String b) {
        return run("{\"operation\":\"" + operation + "\",\"a\":" + a
                + ",\"b\":" + b + "}").output().get("result");
    }

    private String errorFor(String json) {
        return assertThrows(InvalidJobInputException.class, () -> run(json))
                .getMessage();
    }

    @Test
    void addsTheSampleInput() {
        JobExecutionResult result = run("{\"operation\":\"ADD\",\"a\":3,\"b\":7}");

        assertEquals(10L, result.output().get("result"));
        assertEquals(
                "{\"operation\":\"ADD\",\"a\":3,\"b\":7,\"result\":10}",
                result.toJson());
    }

    @Test
    void subtractsMultipliesAndDivides() {
        assertEquals(-4L, resultOf("SUBTRACT", "3", "7"));
        assertEquals(21L, resultOf("MULTIPLY", "3", "7"));
        assertEquals(new BigDecimal("2.5"), resultOf("DIVIDE", "10", "4"));
        assertEquals(5L, resultOf("DIVIDE", "10", "2"));
    }

    @Test
    void operationNameIsCaseInsensitiveAndTrimmed() {
        assertEquals(10L, resultOf("add", "3", "7"));
        assertEquals(10L, resultOf("  Add  ", "3", "7"));
    }

    @Test
    void decimalArithmeticIsExact() {
        assertEquals(new BigDecimal("0.3"), resultOf("ADD", "0.1", "0.2"));
        assertEquals(new BigDecimal("-1.25"), resultOf("SUBTRACT", "1.25", "2.5"));
        assertEquals(3L, resultOf("MULTIPLY", "1.5", "2"));
    }

    @Test
    void nonTerminatingQuotientIsRoundedTo34Digits() {
        assertEquals(
                new BigDecimal("0.3333333333333333333333333333333333"),
                resultOf("DIVIDE", "1", "3"));
    }

    @Test
    void largeIntegersDoNotOverflow() {
        assertEquals(
                new java.math.BigInteger("1000000000000000000000000000000000"),
                resultOf("MULTIPLY", "1000000000000000000", "1000000000000000"));
    }

    @Test
    void powerWorksWithinBounds() {
        assertEquals(1024L, resultOf("POWER", "2", "10"));
        assertEquals(1L, resultOf("POWER", "0", "0"));
        assertEquals(new BigDecimal("0.25"), resultOf("POWER", "0.5", "2"));
    }

    @Test
    void powerRejectsBadExponents() {
        String message = "must be a whole number from 0 to 100";

        assertTrue(errorFor(
                "{\"operation\":\"POWER\",\"a\":2,\"b\":-1}").contains(message));
        assertTrue(errorFor(
                "{\"operation\":\"POWER\",\"a\":2,\"b\":0.5}").contains(message));
        assertTrue(errorFor(
                "{\"operation\":\"POWER\",\"a\":2,\"b\":101}").contains(message));
    }

    @Test
    void powerRejectsResultsThatAreTooLarge() {
        assertTrue(errorFor(
                "{\"operation\":\"POWER\",\"a\":10,\"b\":37}")
                .contains("outside the supported range"));
    }

    @Test
    void divisionByZeroIsRejected() {
        assertEquals("Division by zero is not allowed",
                errorFor("{\"operation\":\"DIVIDE\",\"a\":5,\"b\":0}"));
        assertEquals("Division by zero is not allowed",
                errorFor("{\"operation\":\"DIVIDE\",\"a\":5,\"b\":0.0}"));
    }

    @Test
    void zeroDividedByANumberIsFine() {
        assertEquals(0L, resultOf("DIVIDE", "0", "5"));
    }

    @Test
    void unsupportedOperationsAreRejectedWithTheSupportedList() {
        String message = errorFor("{\"operation\":\"SQRT\",\"a\":4,\"b\":0}");

        assertTrue(message.contains("Unsupported operation 'SQRT'"));
        assertTrue(message.contains("ADD, DIVIDE, MULTIPLY, POWER, SUBTRACT"));
        assertTrue(errorFor("{\"operation\":\"\",\"a\":1,\"b\":1}")
                .contains("Unsupported operation"));
    }

    @Test
    void unsupportedOperationIsReportedBeforeOperandProblems() {
        assertTrue(errorFor("{\"operation\":\"MODULO\"}")
                .contains("Unsupported operation 'MODULO'"));
    }

    @Test
    void veryLongOperationNamesAreTruncatedInTheError() {
        String message = errorFor(
                "{\"operation\":\"" + "X".repeat(500) + "\",\"a\":1,\"b\":1}");

        assertTrue(message.length() < 200);
    }

    @Test
    void rejectsMissingOrNonStringOperation() {
        assertTrue(errorFor("{\"a\":1,\"b\":2}")
                .contains("'operation' is required"));
        assertTrue(errorFor("{\"operation\":5,\"a\":1,\"b\":2}")
                .contains("'operation' is required"));
    }

    @Test
    void rejectsMissingOperands() {
        assertTrue(errorFor("{\"operation\":\"ADD\",\"b\":2}")
                .contains("Field 'a' is required"));
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":1}")
                .contains("Field 'b' is required"));
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":null,\"b\":1}")
                .contains("Field 'a' is required"));
    }

    @Test
    void rejectsMalformedNumericOperands() {
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":\"3\",\"b\":7}")
                .contains("Field 'a' must be a number"));
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":3,\"b\":true}")
                .contains("Field 'b' must be a number"));
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":[1],\"b\":7}")
                .contains("Field 'a' must be a number"));
        assertTrue(errorFor("{\"operation\":\"ADD\",\"a\":{},\"b\":7}")
                .contains("Field 'a' must be a number"));
    }

    @Test
    void rejectsOperandsOutsideTheBounds() {
        assertTrue(errorFor(
                "{\"operation\":\"ADD\",\"a\":1000000000000000001,\"b\":1}")
                .contains("Field 'a' must be between"));
        assertTrue(errorFor(
                "{\"operation\":\"ADD\",\"a\":1,\"b\":-1e19}")
                .contains("Field 'b' must be between"));
        String tooManyDigits = "1" + "0".repeat(100) + "1"; // 102 digits

        assertEquals("Field 'a' is outside the supported numeric range",
                errorFor("{\"operation\":\"ADD\",\"a\":" + tooManyDigits
                        + ",\"b\":1}"));
    }

    @Test
    void rejectsInputThatIsNotAnObject() {
        assertTrue(errorFor("[1,2]").contains("must be a JSON object"));
        assertTrue(errorFor("5").contains("must be a JSON object"));
    }
}
