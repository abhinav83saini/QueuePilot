package com.queuepilot.executor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAnalysisJobExecutorTest {

    private final TextAnalysisJobExecutor executor =
            new TextAnalysisJobExecutor();

    private JobExecutionResult analyse(String text) {
        // Build the JSON through the mapper so the text is escaped properly.
        String json = JsonSupport.toJson(Map.of("text", text));
        return executor.execute(JsonSupport.parse(json));
    }

    private JobExecutionResult run(String json) {
        return executor.execute(JsonSupport.parse(json));
    }

    private String errorFor(String json) {
        return assertThrows(InvalidJobInputException.class, () -> run(json))
                .getMessage();
    }

    @Test
    void analysesTheSampleSentence() {
        JobExecutionResult result = run("{\"text\":\"QueuePilot runs jobs\"}");

        assertEquals(20, result.output().get("characterCount"));
        assertEquals(3, result.output().get("wordCount"));
        assertEquals(1, result.output().get("lineCount"));
        assertEquals(
                List.of(
                        Map.of("word", "jobs", "count", 1),
                        Map.of("word", "queuepilot", "count", 1),
                        Map.of("word", "runs", "count", 1)),
                result.output().get("topWords"));
    }

    @Test
    void emptyTextHasNoCharactersWordsOrLines() {
        JobExecutionResult result = analyse("");

        assertEquals(0, result.output().get("characterCount"));
        assertEquals(0, result.output().get("wordCount"));
        assertEquals(0, result.output().get("lineCount"));
        assertEquals(List.of(), result.output().get("topWords"));
    }

    @Test
    void whitespaceOnlyTextCountsCharactersAndOneLineButNoWords() {
        JobExecutionResult result = analyse("   \t ");

        assertEquals(5, result.output().get("characterCount"));
        assertEquals(0, result.output().get("wordCount"));
        assertEquals(1, result.output().get("lineCount"));
    }

    @Test
    void countsMultipleLinesForAllLineBreakStyles() {
        assertEquals(3, analyse("one\ntwo\nthree").output().get("lineCount"));
        assertEquals(3, analyse("one\r\ntwo\r\nthree").output().get("lineCount"));
        assertEquals(3, analyse("one\rtwo\rthree").output().get("lineCount"));
    }

    @Test
    void trailingLineBreakDoesNotStartANewLine() {
        assertEquals(1, analyse("one\n").output().get("lineCount"));
        assertEquals(2, analyse("one\ntwo\r\n").output().get("lineCount"));
    }

    @Test
    void blankLinesAreCounted() {
        assertEquals(3, analyse("one\n\ntwo").output().get("lineCount"));
        assertEquals(1, analyse("\n").output().get("lineCount"));
        assertEquals(2, analyse("\n\n").output().get("lineCount"));
    }

    @Test
    void wordsAreSplitOnAnyWhitespaceAcrossLines() {
        JobExecutionResult result = analyse("  hello \t world\nagain  ");

        assertEquals(3, result.output().get("wordCount"));
        assertEquals(2, result.output().get("lineCount"));
    }

    @Test
    void tokensWithoutLettersOrDigitsAreNotWords() {
        JobExecutionResult result = analyse("a - b ... c");

        assertEquals(3, result.output().get("wordCount"));
    }

    @Test
    void topWordsAreCaseFoldedStrippedAndRanked() {
        JobExecutionResult result =
                analyse("The cat. the dog! THE bird, cat?");

        assertEquals(
                List.of(
                        Map.of("word", "the", "count", 3),
                        Map.of("word", "cat", "count", 2),
                        Map.of("word", "bird", "count", 1),
                        Map.of("word", "dog", "count", 1)),
                result.output().get("topWords"));
        assertEquals(7, result.output().get("wordCount"));
    }

    @Test
    void topWordsAreLimitedToFive() {
        JobExecutionResult result = analyse("a b c d e f g h");

        assertEquals(8, result.output().get("wordCount"));
        assertEquals(5, ((List<?>) result.output().get("topWords")).size());
    }

    @Test
    void countsUnicodeCodePointsNotUtf16Units() {
        // One emoji is two UTF-16 chars but a single character.
        JobExecutionResult result = analyse("a😀");

        assertEquals(2, result.output().get("characterCount"));
    }

    @Test
    void rejectsMissingOrNonStringText() {
        assertTrue(errorFor("{}").contains("'text' is required"));
        assertTrue(errorFor("{\"text\":null}").contains("'text' is required"));
        assertTrue(errorFor("{\"text\":42}").contains("must be a string"));
        assertTrue(errorFor("{\"text\":[\"a\"]}").contains("must be a string"));
    }

    @Test
    void rejectsInputThatIsNotAnObject() {
        assertTrue(errorFor("\"just text\"").contains("must be a JSON object"));
        assertTrue(errorFor("[]").contains("must be a JSON object"));
    }

    @Test
    void rejectsTextOverTheLengthLimit() {
        String tooLong = "a".repeat(TextAnalysisJobExecutor.MAX_TEXT_LENGTH + 1);

        InvalidJobInputException error = assertThrows(
                InvalidJobInputException.class, () -> analyse(tooLong));

        assertTrue(error.getMessage().contains("at most "
                + TextAnalysisJobExecutor.MAX_TEXT_LENGTH));
    }

    @Test
    void acceptsTextAtTheLengthLimit() {
        String atLimit = "a".repeat(TextAnalysisJobExecutor.MAX_TEXT_LENGTH);

        assertEquals(TextAnalysisJobExecutor.MAX_TEXT_LENGTH,
                analyse(atLimit).output().get("characterCount"));
    }
}
