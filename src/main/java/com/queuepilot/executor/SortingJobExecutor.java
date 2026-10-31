package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts a list of numbers.
 *
 * <p><b>Job types:</b> {@code SORT}, {@code SORTING}</p>
 *
 * <p><b>Input:</b> {@code {"values":[5,2,4]}}, with an optional
 * {@code "order"} of {@code "ASC"} (default) or {@code "DESC"}.</p>
 *
 * <p><b>Output:</b> {@code {"sortedValues":[2,4,5],"count":3}}</p>
 *
 * <p><b>Rules:</b> {@code values} is required and must be an array of JSON
 * numbers (strings, booleans, null and nested arrays are rejected, and the
 * error names the offending index). An empty array is valid and yields
 * {@code {"sortedValues":[],"count":0}}. At most {@value #MAX_VALUES} values
 * are accepted. Whole numbers are reported without a fractional part, so
 * {@code 5.0} appears as {@code 5}.</p>
 */
@Component
public class SortingJobExecutor implements JobExecutor {

    static final int MAX_VALUES = 10_000;

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("SORT", "SORTING");
    }

    @Override
    public void validateInput(JsonNode input) {
        Map<String, Object> data = JobInputs.requireObject(input);

        Object raw = data.get("values");

        if (raw == null) {
            throw new InvalidJobInputException(
                    "Field 'values' is required and must be an array of numbers");
        }

        if (!(raw instanceof List<?> list)) {
            throw new InvalidJobInputException(
                    "Field 'values' must be an array of numbers");
        }

        if (list.size() > MAX_VALUES) {
            throw new InvalidJobInputException(
                    "Field 'values' must contain at most " + MAX_VALUES
                            + " numbers (received " + list.size() + ")");
        }

        isDescending(data);

        for (int i = 0; i < list.size(); i++) {
            JobInputs.toNumber(list.get(i), "values[" + i + "]");
        }
    }

    @Override
    public JobExecutionResult execute(JsonNode input) {
        validateInput(input);
        Map<String, Object> data = JobInputs.requireObject(input);
        List<?> list = (List<?>) data.get("values");

        boolean descending = isDescending(data);

        List<BigDecimal> numbers = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++) {
            numbers.add(JobInputs.toNumber(list.get(i), "values[" + i + "]"));
        }

        numbers.sort(descending
                ? Comparator.<BigDecimal>naturalOrder().reversed()
                : Comparator.<BigDecimal>naturalOrder());

        List<Number> sorted = new ArrayList<>(numbers.size());

        for (BigDecimal number : numbers) {
            sorted.add(JobInputs.normalize(number));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sortedValues", sorted);
        output.put("count", sorted.size());

        return JobExecutionResult.of(output);
    }

    private static boolean isDescending(Map<String, Object> data) {
        if (!data.containsKey("order")) {
            return false;
        }

        Object order = data.get("order");

        if (order instanceof String text) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);

            if (normalized.equals("ASC")) {
                return false;
            }

            if (normalized.equals("DESC")) {
                return true;
            }
        }

        throw new InvalidJobInputException(
                "Field 'order' must be \"ASC\" or \"DESC\"");
    }
}
