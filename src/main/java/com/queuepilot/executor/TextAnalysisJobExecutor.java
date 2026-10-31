package com.queuepilot.executor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Counts characters, words and lines in a piece of text.
 *
 * <p><b>Job type:</b> {@code TEXT_ANALYSIS}</p>
 *
 * <p><b>Input:</b> {@code {"text":"QueuePilot runs jobs"}}</p>
 *
 * <p><b>Output:</b></p>
 * <pre>{"characterCount":20,"wordCount":3,"lineCount":1,
 *  "topWords":[{"word":"jobs","count":1}, ...]}</pre>
 *
 * <p><b>Counting rules</b></p>
 * <ul>
 *   <li><b>characterCount</b>: Unicode code points, including spaces and
 *       line breaks (an emoji counts as one).</li>
 *   <li><b>wordCount</b>: the text is split on whitespace; a resulting token
 *       is a word only if it contains at least one letter or digit, so a
 *       lone {@code -} is not a word.</li>
 *   <li><b>lineCount</b>: {@code \n}, {@code \r\n} and {@code \r} each end a
 *       line. Empty text has 0 lines; any other text has one more line than
 *       its number of line breaks, unless it ends with a line break (a
 *       trailing break does not start a new line). Whitespace-only text such
 *       as {@code "   "} therefore has 1 line, 0 words.</li>
 *   <li><b>topWords</b>: up to {@value #TOP_WORDS} most frequent words, case-folded
 *       and stripped of leading/trailing punctuation, ordered by count
 *       (descending) then alphabetically.</li>
 * </ul>
 *
 * <p>Empty text is valid. Text longer than {@value #MAX_TEXT_LENGTH}
 * characters is rejected.</p>
 */
@Component
public class TextAnalysisJobExecutor implements JobExecutor {

    static final int MAX_TEXT_LENGTH = 1_000_000;
    static final int TOP_WORDS = 5;

    @Override
    public Set<String> supportedJobTypes() {
        return Set.of("TEXT_ANALYSIS");
    }

    @Override
    public void validateInput(JsonNode input) {
        Map<String, Object> data = JobInputs.requireObject(input);
        String text = JobInputs.requireString(data, "text");

        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidJobInputException(
                    "Field 'text' must be at most " + MAX_TEXT_LENGTH
                            + " characters (received " + text.length() + ")");
        }
    }

    @Override
    public JobExecutionResult execute(JsonNode input) {
        validateInput(input);
        Map<String, Object> data = JobInputs.requireObject(input);
        String text = JobInputs.requireString(data, "text");

        Map<String, Integer> frequencies = new HashMap<>();
        int wordCount = countWords(text, frequencies);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("characterCount", text.codePointCount(0, text.length()));
        output.put("wordCount", wordCount);
        output.put("lineCount", countLines(text));
        output.put("topWords", topWords(frequencies));

        return JobExecutionResult.of(output);
    }

    private static int countWords(String text, Map<String, Integer> frequencies) {
        StringBuilder token = new StringBuilder();
        int words = 0;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (isSpace(codePoint)) {
                words += flush(token, frequencies);
            } else {
                token.appendCodePoint(codePoint);
            }
        }

        return words + flush(token, frequencies);
    }

    /** Ends the current token; returns 1 if it was a word, otherwise 0. */
    private static int flush(StringBuilder token, Map<String, Integer> frequencies) {
        if (token.length() == 0) {
            return 0;
        }

        int[] codePoints = token.codePoints().toArray();
        token.setLength(0);

        int first = 0;
        while (first < codePoints.length
                && !Character.isLetterOrDigit(codePoints[first])) {
            first++;
        }

        if (first == codePoints.length) {
            return 0;
        }

        int last = codePoints.length - 1;
        while (!Character.isLetterOrDigit(codePoints[last])) {
            last--;
        }

        String word = new String(codePoints, first, last - first + 1)
                .toLowerCase(Locale.ROOT);
        frequencies.merge(word, 1, Integer::sum);

        return 1;
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }

        int lines = 0;
        int length = text.length();

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                lines++;
            } else if (c == '\r') {
                lines++;

                if (i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }

        char last = text.charAt(length - 1);

        if (last != '\n' && last != '\r') {
            lines++;
        }

        return lines;
    }

    private static List<Map<String, Object>> topWords(
            Map<String, Integer> frequencies) {

        return frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_WORDS)
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("word", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .toList();
    }
}
