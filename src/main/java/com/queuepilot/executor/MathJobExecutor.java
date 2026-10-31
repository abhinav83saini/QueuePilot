package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Performs one arithmetic operation on two numbers.
 *
 * <p><b>Job type:</b> {@code MATH}</p>
 *
 * <p><b>Input:</b> {@code {"operation":"ADD","a":3,"b":7}}</p>
 *
 * <p><b>Output:</b> {@code {"operation":"ADD","a":3,"b":7,"result":10}}</p>
 *
 * <p><b>Operations</b> (case-insensitive): {@code ADD}, {@code SUBTRACT},
 * {@code MULTIPLY}, {@code DIVIDE} and, as a bounded extra, {@code POWER}.</p>
 *
 * <p><b>Rules and bounds</b></p>
 * <ul>
 *   <li>{@code a} and {@code b} must be JSON numbers (not strings), at most
 *       10<sup>18</sup> in magnitude, with at most 100 significant digits and
 *       at most 100 decimal places.</li>
 *   <li>Arithmetic uses {@link BigDecimal}, so {@code 0.1 + 0.2} is exactly
 *       {@code 0.3} and nothing overflows silently.</li>
 *   <li>{@code DIVIDE} by zero is rejected; quotients are rounded to 34
 *       significant digits.</li>
 *   <li>{@code POWER} requires {@code b} to be a whole number from 0 to
 *       {@value #MAX_EXPONENT} (so {@code 0^0 = 1}) and rounds to 100
 *       significant digits.</li>
 *   <li>Any result larger than 10<sup>36</sup> in magnitude is rejected.</li>
 * </ul>
 */
@Component
public class MathJobExecutor implements JobExecutor {

    static final int MAX_EXPONENT = 100;

    private static final BigDecimal MAX_OPERAND = BigDecimal.TEN.pow(18);
    private static final BigDecimal MAX_RESULT = BigDecimal.TEN.pow(36);
    private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;
    private static final MathContext POWER_CONTEXT = new MathContext(100);

    private static final Set<String> OPERATIONS = new TreeSet<>(
            Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "POWER"));

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("MATH");
    }

    @Override
    public void validateInput(JsonNode input) {
        Map<String, Object> data = JobInputs.requireObject(input);

        String operation = JobInputs.requireString(data, "operation")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!OPERATIONS.contains(operation)) {
            throw new InvalidJobInputException(
                    "Unsupported operation '" + abbreviate(operation)
                            + "'. Supported operations: "
                            + String.join(", ", OPERATIONS));
        }

        operand(data, "a");
        BigDecimal b = operand(data, "b");

        if ("DIVIDE".equals(operation) && b.signum() == 0) {
            throw new InvalidJobInputException("Division by zero is not allowed");
        }

        if ("POWER".equals(operation)) {
            exponent(b);
        }
    }

    @Override
    public JobExecutionResult execute(JsonNode input) {
        validateInput(input);
        Map<String, Object> data = JobInputs.requireObject(input);

        String operation = JobInputs.requireString(data, "operation")
                .trim()
                .toUpperCase(Locale.ROOT);

        BigDecimal a = operand(data, "a");
        BigDecimal b = operand(data, "b");

        BigDecimal result;

        try {
            result = compute(operation, a, b);
        } catch (ArithmeticException ex) {
            throw new InvalidJobInputException(
                    "Arithmetic error while computing " + operation);
        }

        if (result.abs().compareTo(MAX_RESULT) > 0) {
            throw new InvalidJobInputException(
                    "Result is outside the supported range (magnitude above 10^36)");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("operation", operation);
        output.put("a", JobInputs.normalize(a));
        output.put("b", JobInputs.normalize(b));
        output.put("result", JobInputs.normalize(result));

        return JobExecutionResult.of(output);
    }

    private static BigDecimal compute(
            String operation, BigDecimal a, BigDecimal b) {

        return switch (operation) {
            case "ADD" -> a.add(b);
            case "SUBTRACT" -> a.subtract(b);
            case "MULTIPLY" -> a.multiply(b);
            case "DIVIDE" -> {
                if (b.signum() == 0) {
                    throw new InvalidJobInputException(
                            "Division by zero is not allowed");
                }
                yield a.divide(b, DIVISION_CONTEXT);
            }
            case "POWER" -> a.pow(exponent(b), POWER_CONTEXT);
            default -> throw new InvalidJobInputException(
                    "Unsupported operation '" + abbreviate(operation) + "'");
        };
    }

    private static int exponent(BigDecimal b) {
        if (b.scale() > 0
                || b.signum() < 0
                || b.compareTo(BigDecimal.valueOf(MAX_EXPONENT)) > 0) {
            throw new InvalidJobInputException(
                    "For POWER, 'b' must be a whole number from 0 to "
                            + MAX_EXPONENT);
        }

        return b.intValueExact();
    }

    private static BigDecimal operand(Map<String, Object> data, String field) {
        if (!data.containsKey(field) || data.get(field) == null) {
            throw new InvalidJobInputException(
                    "Field '" + field + "' is required and must be a number");
        }

        BigDecimal value = JobInputs.toNumber(
                data.get(field), "Field '" + field + "'");

        if (value.abs().compareTo(MAX_OPERAND) > 0) {
            throw new InvalidJobInputException(
                    "Field '" + field + "' must be between -10^18 and 10^18");
        }

        return value;
    }

    /** Keeps client-supplied text short before it appears in an error. */
    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
