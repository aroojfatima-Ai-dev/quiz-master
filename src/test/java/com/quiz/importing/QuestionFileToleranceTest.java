package com.quiz.importing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ways a questions file survives the trip through a PDF or a Word document.
 *
 * <p>Everything here was added on top of the line-per-field parser because real files are
 * not always shaped the way the instructions ask: a text file is often written as one
 * question per line, an answer is often the option text rather than a letter, and text
 * extracted from a PDF carries page breaks, non-breaking spaces and a byte order mark.
 * The rule the tests below pin down is the important one: tolerate what can be
 * interpreted without guessing, and report the rest.
 */
class QuestionFileToleranceTest {

    // ------------------------------------------------------------------
    // Single-line questions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a whole question on one line is expanded into its fields")
    void parsesQuestionOnOneLine() {
        ImportResult result = QuestionFileParser.parse(
                "Q: What is the capital of France? A) Berlin B) Paris C) Madrid D) Rome "
                        + "Answer: B Topic: Geography Difficulty: EASY");

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(1, result.getQuestions().size());

        ParsedQuestion question = result.getQuestions().get(0);
        assertEquals("What is the capital of France?", question.getQuestionText());
        assertEquals("Berlin", question.getOptionA());
        assertEquals("Paris", question.getOptionB());
        assertEquals("Madrid", question.getOptionC());
        assertEquals("Rome", question.getOptionD());
        assertEquals("B", question.getCorrectOption());
        assertEquals("Geography", question.getTopic());
        assertEquals("EASY", question.getDifficulty());
    }

    @Test
    @DisplayName("several questions on one line each become their own question")
    void splitsMultipleInlineQuestions() {
        ImportResult result = QuestionFileParser.parse(
                "Q: First? A) a1 B) b1 C) c1 D) d1 Answer: A "
                        + "Q: Second? A) a2 B) b2 C) c2 D) d2 Answer: D");

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(2, result.getQuestions().size());
        assertEquals("First?", result.getQuestions().get(0).getQuestionText());
        assertEquals("A", result.getQuestions().get(0).getCorrectOption());
        assertEquals("Second?", result.getQuestions().get(1).getQuestionText());
        assertEquals("D", result.getQuestions().get(1).getCorrectOption());
    }

    @Test
    @DisplayName("an inline question with the answer on the next line still works")
    void inlineQuestionWithAnswerOnItsOwnLine() {
        ImportResult result = QuestionFileParser.parse(
                "Q: Which keyword declares a constant? A) static B) final C) const D) var\n"
                        + "Answer: B\n");

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(1, result.getQuestions().size());
        assertEquals("final", result.getQuestions().get(0).getOptionB());
        assertEquals("B", result.getQuestions().get(0).getCorrectOption());
    }

    @Test
    @DisplayName("a normal one-field-per-line file is not touched by the inline handling")
    void leavesOrdinaryFilesAlone() {
        String file = """
                Q: Which of these is a primary colour?
                A) green
                B) orange
                C) red
                D) purple
                Answer: C
                Topic: Art
                Difficulty: EASY
                """;

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(1, result.getQuestions().size());
        assertEquals("Which of these is a primary colour?", result.getQuestions().get(0).getQuestionText());
        assertEquals("red", result.getQuestions().get(0).getOptionC());
    }

    @Test
    @DisplayName("a bracketed letter inside the question text does not split the line")
    void doesNotSplitOnBracketedReferences() {
        // "(B)" is not an inline option marker, so this stays one question - which is why
        // bracketed markers are only accepted at the start of a line.
        ImportResult result = QuestionFileParser.parse("""
                Q: Which statement about the diagram (B) is true?
                A) the first part
                B) the second part
                C) the part marked (B) in the diagram
                D) none of them
                Answer: C
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        ParsedQuestion question = result.getQuestions().get(0);
        assertEquals("Which statement about the diagram (B) is true?", question.getQuestionText());
        assertEquals("the part marked (B) in the diagram", question.getOptionC());
    }

    @Test
    @DisplayName("an inline question that is missing an option is reported, not half-read")
    void reportsIncompleteInlineQuestion() {
        ImportResult result = QuestionFileParser.parse(
                "Q: Broken? A) one B) two D) four Answer: B");

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size(), "an unreadable line must be reported: " + result);
    }

    // ------------------------------------------------------------------
    // Answers written as text
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an answer written as the option text is matched to its letter")
    void matchesAnswerWrittenAsText() {
        ImportResult result = QuestionFileParser.parse("""
                Q: Which planet is known as the Red Planet?
                A) Venus
                B) Mars
                C) Jupiter
                D) Saturn
                Answer: Mars
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals("B", result.getQuestions().get(0).getCorrectOption());
    }

    @Test
    @DisplayName("an answer that merely starts with an option text still matches")
    void matchesAnswerWithTrailingWords() {
        ImportResult result = QuestionFileParser.parse("""
                Q: Which ocean is the largest?
                A) Indian Ocean
                B) Atlantic Ocean
                C) Pacific Ocean
                D) Arctic Ocean
                Answer: Pacific Ocean - it covers about a third of the planet.
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals("C", result.getQuestions().get(0).getCorrectOption());
    }

    @Test
    @DisplayName("an ambiguous or unknown text answer is reported instead of guessed")
    void reportsAmbiguousTextAnswer() {
        ImportResult ambiguous = QuestionFileParser.parse("""
                Q: Which weight is correct?
                A) 3
                B) 3 kg
                C) 5 kg
                D) 7 kg
                Answer: 3 kg
                """);
        assertEquals(0, ambiguous.getQuestions().size(), "two options fit, so nothing may be picked");

        ImportResult unknown = QuestionFileParser.parse("""
                Q: Which one?
                A) alpha
                B) beta
                C) gamma
                D) delta
                Answer: epsilon
                """);
        assertEquals(0, unknown.getQuestions().size());
        assertTrue(unknown.getFailures().get(0).getReason().contains("epsilon"),
                "the reason quotes what the file said: " + unknown.getFailures().get(0).getReason());
    }

    // ------------------------------------------------------------------
    // Difficulty spelling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("difficulties written as words map to the nearest level, not to MEDIUM")
    void mapsDifficultySynonyms() {
        assertEquals("EASY", difficultyOf("Easy"));
        assertEquals("HARD", difficultyOf("Difficult"));
        assertEquals("HARD", difficultyOf("Hard (level 3)"));
        assertEquals("MEDIUM", difficultyOf("Med"));
        assertEquals("EASY", difficultyOf("easy"));
        assertEquals("MEDIUM", difficultyOf("MEDIUM"));

        // Anything genuinely unrecognised keeps the documented fallback.
        assertEquals("MEDIUM", difficultyOf("IMPOSSIBLE"));
    }

    // ------------------------------------------------------------------
    // Extraction artefacts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("page breaks, non-breaking spaces and a BOM do not break a file")
    void survivesExtractionArtefacts() {
        // \f is where a PDF page ends; \u00A0 is what Word puts after "Answer:"; the
        // leading \uFEFF is the byte order mark a Windows editor writes.
        String file = "\uFEFFQ: In a binary search tree, where is the\r\n"
                + "smallest key always found?\r\n"
                + "A) At the root\u00A0\u00A0\r\n"
                + "B) Left-most node\r\n"
                + "C) Right-most node\r\n"
                + "D) In any leaf\r\n"
                + "Answer:\u00A0B\r\n"
                + "\f"
                + "Q: Second question?\n"
                + "A) yes\nB) no\nC) maybe\nD) never\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals(2, result.getQuestions().size(), "the second page must not be swallowed");

        ParsedQuestion first = result.getQuestions().get(0);
        assertEquals("In a binary search tree, where is the smallest key always found?",
                first.getQuestionText());
        assertEquals("At the root", first.getOptionA());
        assertEquals("B", first.getCorrectOption());
        assertEquals("Second question?", result.getQuestions().get(1).getQuestionText());
    }

    @Test
    @DisplayName("an index answer is not confused with an option marker")
    void doesNotTreatTheAnswerAsAnOption() {
        ImportResult result = QuestionFileParser.parse("""
                Q: How many bits are in a byte?
                A) 2
                B) 4
                C) 8
                D) 16
                Answer: C)
                """);

        assertEquals(0, result.getFailures().size(), "failures: " + result.getFailures());
        assertEquals("C", result.getQuestions().get(0).getCorrectOption());
        assertNotEquals("", result.getQuestions().get(0).getOptionD());
    }

    private static String difficultyOf(String difficulty) {
        ImportResult result = QuestionFileParser.parse("Q: How hard is this?\n"
                + "A) one\nB) two\nC) three\nD) four\n"
                + "Answer: A\nDifficulty: " + difficulty + "\n");
        assertEquals(1, result.getQuestions().size(),
                "the question must survive Difficulty: " + difficulty + " -> " + result.getFailures());
        return result.getQuestions().get(0).getDifficulty();
    }
}
