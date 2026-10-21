package com.queuepilot.executor;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * Shared input-validation helpers for the built-in executors (SRS 4.2:
 * lengths and numeric ranges are validated before they affect execution).
 */
final class JobInputs {

    /** Maximum significant digits accepted for any numeric input. */
    static final int MAX_DIGITS = 100;

    /** Maximum magnitude of the (trailing-zero-stripped) scale of a number. */
    static final int MAX_ABS_SCALE = 100;

    private JobInputs() {
    }

    /** Requires the input to be a JSON object and returns it as a Map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> requireObject(JsonNode input) {
        Object value = JsonSupport.toJava(input);

        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        throw new InvalidJobInputException(
                "Job input must be a JSON object");
    }

    /** Requires {@code field} to be present and a JSON string (may be empty). */
    static String requireString(Map<String, Object> input, String field) {
        Object value = input.get(field);

        if (value instanceof String text) {
            return text;
        }

        throw new InvalidJobInputException(
                "Field '" + field + "' is required and must be a string");
    }

    /**
     * Converts a parsed JSON value to a validated number.
     *
     * <p>Only JSON numbers are accepted (numeric strings, booleans and null
     * are rejected). Numbers with more than {@value #MAX_DIGITS} significant
     * digits or an extreme exponent are rejected so later arithmetic and
     * output cannot become expensive.</p>
     *
     * @param label how to refer to the value in error messages
     * @return the number with trailing zeros removed
     */
    static BigDecimal toNumber(Object value, String label) {
        BigDecimal number;

        if (value instanceof BigDecimal decimal) {
            number = decimal;
        } else if (value instanceof BigInteger integer) {
            number = new BigDecimal(integer);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();

            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new InvalidJobInputException(
                        label + " must be a finite number");
            }

            number = BigDecimal.valueOf(d);
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            number = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new InvalidJobInputException(
                    label + " must be a number");
        }

        BigDecimal stripped = strip(number);

        if (stripped.precision() > MAX_DIGITS
                || Math.abs(stripped.scale()) > MAX_ABS_SCALE) {
            throw new InvalidJobInputException(
                    label + " is outside the supported numeric range");
        }

        return stripped;
    }

    /** Removes trailing zeros; zero is always {@link BigDecimal#ZERO}. */
    static BigDecimal strip(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    /**
     * Converts a number to its canonical output form: a {@code Long} or
     * {@code BigInteger} for whole numbers (so {@code 5.0} is reported as
     * {@code 5}), otherwise a {@code BigDecimal} without trailing zeros.
     */
    static Number normalize(BigDecimal value) {
        BigDecimal stripped = strip(value);

        if (stripped.scale() <= 0) {
            BigInteger whole = stripped.toBigIntegerExact();

            return whole.bitLength() < 64
                    ? (Number) Long.valueOf(whole.longValue())
                    : whole;
        }

        return stripped;
    }
}
