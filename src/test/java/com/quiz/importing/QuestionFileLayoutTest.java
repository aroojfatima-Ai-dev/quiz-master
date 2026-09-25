package com.quiz.importing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the ways a questions file is laid out that used to be rejected.
 *
 * <p>Each test below documents a layout a teacher can reasonably produce that the parser
 * refused, or refused with a reason that pointed at the wrong place. The rule the parser
 * follows is: split a line into fields only when doing so cannot misread the line, and
 * report everything else against the line it actually came from.
 */
class QuestionFileLayoutTest {

    // ------------------------------------------------------------------
    // Options and answer on one line, question on the line above
    // ------------------------------------------------------------------

    @Test
    @DisplayName("options and answer on one line, with the question on the line above")
    void parsesOptionsOnOneLineUnderTheQuestion() {
        ImportResult result = QuestionFileParser.parse("""
                Q: What is the capital of France?
                A) Berlin B) Paris C) Madrid D) Rome Answer: B
                Q: Which keyword declares a constant in Java?
                A) static B) final C) const D) var Answer: B
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(2, result.getQuestions().size());

        ParsedQuestion first = result.getQuestions().get(0);
        assertEquals("What is the capital of France?", first.getQuestionText());
        assertEquals("Berlin", first.getOptionA());
        assertEquals("Paris", first.getOptionB());
        assertEquals("Madrid", first.getOptionC());
        assertEquals("Rome", first.getOptionD());
        assertEquals("B", first.getCorrectOption());

        ParsedQuestion second = result.getQuestions().get(1);
        assertEquals("Which keyword declares a constant in Java?", second.getQuestionText());
        assertEquals("var", second.getOptionD());
        assertEquals("B", second.getCorrectOption());
    }

    @Test
    @DisplayName("an options line carrying a keyword is split even with no Answer on it")
    void parsesOptionsLineWithOnlyTheQuestionKeyword() {
        ImportResult result = QuestionFileParser.parse("""
                Question 1: Which planet is closest to the Sun?
                A) Venus B) Mercury C) Earth D) Mars Answer: B
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(1, result.getQuestions().size());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("Which planet is closest to the Sun?", q.getQuestionText());
        assertEquals("Mercury", q.getOptionB());
        assertEquals("B", q.getCorrectOption());
    }

    // ------------------------------------------------------------------
    // Lines that must NOT be split
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a wrapped option that enumerates markers is not cut apart")
    void doesNotSplitWrappedOptionEnumeratingMarkers() {
        // No keyword label anywhere on the line, so it is a continuation of option A and
        // must be left exactly as written. Splitting it would silently invent options.
        ImportResult result = QuestionFileParser.parse("""
                Q: Which one?
                A) the sequence A) 1 B) 2 C) 3 D) 4 is the key
                B) b
                C) c
                D) d
                Answer: A
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("the sequence A) 1 B) 2 C) 3 D) 4 is the key", q.getOptionA());
        assertEquals("b", q.getOptionB());
        assertEquals("A", q.getCorrectOption());
    }

    @Test
    @DisplayName("a one-field-per-line file is still left exactly as it is")
    void ordinaryFileIsUntouched() {
        ImportResult result = QuestionFileParser.parse("""
                Q: Which of these is a primary colour?
                A) green
                B) orange
                C) red
                D) purple
                Answer: C
                Topic: Art
                Difficulty: EASY
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("Which of these is a primary colour?", q.getQuestionText());
        assertEquals("red", q.getOptionC());
        assertEquals("C", q.getCorrectOption());
        assertEquals("Art", q.getTopic());
        assertEquals("EASY", q.getDifficulty());
    }

    // ------------------------------------------------------------------
    // Failure line numbers must be the lines in the file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a skipped block is reported at the line it is on, not after an expanded one")
    void failureLineNumbersSurviveInlineExpansion() {
        // Lines 1-6: a normal question. Line 8: an inline question, which the parser
        // expands into eight lines internally. Lines 10-13: a broken question whose Q: is
        // physically on line 10. Before the fix the failure was reported at line 15.
        String file =
                "Q: Normal one?\n"                     // 1
              + "A) a\nB) b\nC) c\nD) d\n"             // 2-5
              + "Answer: A\n"                          // 6
              + "\n"                                   // 7
              + "Q: Inline? A) a B) b C) c D) d Answer: A\n"  // 8
              + "\n"                                   // 9
              + "Q: Broken one?\n"                     // 10
              + "A) a\nB) b\nAnswer: A\n";             // 11-13

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(2, result.getQuestions().size());
        assertEquals(1, result.getFailures().size(), "only the broken block is skipped");
        ParseFailure failure = result.getFailures().get(0);
        assertEquals(10, failure.getLineNumber(), "the Q: line of the broken block is line 10");
        assertTrue(failure.getSnippet().contains("Broken one?"), failure.getSnippet());
    }

    @Test
    @DisplayName("line numbers stay correct for a block skipped after several inline questions")
    void failureLineNumbersAfterManyInlineQuestions() {
        StringBuilder file = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            file.append("Q: Inline ").append(i).append("? A) a B) b C) c D) d Answer: A\n");
        }
        // Physical line 4 is the broken block's Q: line.
        file.append("Q: Broken?\nA) a\nB) b\nAnswer: A\n");

        ImportResult result = QuestionFileParser.parse(file.toString());

        assertEquals(3, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertEquals(4, result.getFailures().get(0).getLineNumber(),
                "the fourth physical line is where the broken block starts");
    }

    @Test
    @DisplayName("a question that is complete on one line still reports the right line")
    void inlineQuestionLineNumbersAreReported() {
        // The inline question is on line 3, and it is broken (no option D).
        ImportResult result = QuestionFileParser.parse("""
                A worksheet header
                Q: Fine? A) a B) b C) c D) d Answer: A
                Q: Broken? A) a B) b C) c Answer: A
                """);

        assertEquals(1, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertEquals(3, result.getFailures().get(0).getLineNumber());
        assertNotNull(result.getFailures().get(0).getReason());
    }

    // ------------------------------------------------------------------
    // Degenerate input still never throws
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every layout above is also accepted with CRLF line endings")
    void handlesCrlfForTheNewLayouts() {
        ImportResult result = QuestionFileParser.parse(
                "Q: Capital of France?\r\nA) Berlin B) Paris C) Madrid D) Rome Answer: B\r\n");

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(1, result.getQuestions().size());
        assertEquals("Rome", result.getQuestions().get(0).getOptionD());
        assertEquals("B", result.getQuestions().get(0).getCorrectOption());
    }

    @Test
    @DisplayName("a line of only option markers with no keyword is reported, not guessed")
    void reportsBareMarkerRun() {
        ImportResult result = QuestionFileParser.parse("""
                Q: Which?
                A) a B) b C) c D) d
                Answer: A
                """);

        // No keyword label on the options line, so it is left alone: option A swallows the
        // rest, B/C/D are missing, and the block is reported with the reason rather than
        // being cut apart on a guess.
        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getReason().contains("Missing option B"),
                result.getFailures().get(0).getReason());
    }
}
