package com.quiz.importing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link QuestionFileParser}.
 *
 * <p>No window and no database are involved, which is the point of keeping the parser
 * in its own package: every rule the upload screen promises is pinned here.
 */
class QuestionFileParserTest {

    /**
     * The exact example printed as instructions on the upload screen. If this fails,
     * the app is asking teachers for a format it cannot itself read.
     */
    @Test
    @DisplayName("the documented format example parses")
    void parsesTheDocumentedExample() {
        ImportResult result = QuestionFileParser.parse(QuestionFileParser.DOCUMENTED_FORMAT);

        assertEquals(1, result.getQuestions().size(), "the example should yield one question");
        assertEquals(0, result.getFailures().size(), "the example should yield no failures: "
                + result.getFailures());

        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("<question text>", q.getQuestionText());
        assertEquals("<option A>", q.getOptionA());
        assertEquals("<option B>", q.getOptionB());
        assertEquals("<option C>", q.getOptionC());
        assertEquals("<option D>", q.getOptionD());
        assertEquals("A", q.getCorrectOption());
        assertEquals("<topic text>", q.getTopic());
        assertEquals("MEDIUM", q.getDifficulty(), "<EASY, MEDIUM, or HARD> -> first keyword wins is not intended; "
                + "an unrecognised difficulty falls back to MEDIUM");
    }

    @Test
    @DisplayName("a complete file parses, preserving file order")
    void preservesOrder() {
        String file =
                "Q: First question?\n"
              + "A) a1\nB) b1\nC) c1\nD) d1\n"
              + "Answer: A\n"
              + "Topic: Alpha\n"
              + "Difficulty: EASY\n"
              + "\n"
              + "Q: Second question?\n"
              + "A) a2\nB) b2\nC) c2\nD) d2\n"
              + "Answer: B\n"
              + "Topic: Beta\n"
              + "Difficulty: HARD\n"
              + "\n"
              + "Q: Third question?\n"
              + "A) a3\nB) b3\nC) c3\nD) d3\n"
              + "Answer: C\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        List<ParsedQuestion> qs = result.getQuestions();
        assertEquals(3, qs.size());
        assertEquals("First question?", qs.get(0).getQuestionText());
        assertEquals("Second question?", qs.get(1).getQuestionText());
        assertEquals("Third question?", qs.get(2).getQuestionText());
        assertEquals("A", qs.get(0).getCorrectOption());
        assertEquals("B", qs.get(1).getCorrectOption());
        assertEquals("C", qs.get(2).getCorrectOption());
    }

    @Test
    @DisplayName("missing Topic and Difficulty fall back to General / MEDIUM")
    void appliesDefaults() {
        String file =
                "Q: No topic or difficulty here?\n"
              + "A) one\nB) two\nC) three\nD) four\n"
              + "Answer: D\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("General", q.getTopic());
        assertEquals("MEDIUM", q.getDifficulty());
    }

    @Test
    @DisplayName("an unrecognised difficulty falls back to MEDIUM instead of failing")
    void unknownDifficultyFallsBack() {
        String file =
                "Q: Tricky one?\nA) a\nB) b\nC) c\nD) d\nAnswer: A\nDifficulty: Quite hard\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        assertEquals("MEDIUM", result.getQuestions().get(0).getDifficulty());
    }

    // ------------------------------------------------------------------
    // Text extracted from PDF/DOCX wraps lines - the parser must cope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a question wrapped over several lines stays one question")
    void joinsWrappedQuestionText() {
        String file =
                "Q: What is the capital\n"
              + "of France, the largest\n"
              + "city in the country?\n"
              + "A) Paris\nB) Lyon\nC) Nice\nD) Lille\n"
              + "Answer: A\n"
              + "Topic: Geography\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        assertEquals(1, result.getQuestions().size());
        assertEquals("What is the capital of France, the largest city in the country?",
                result.getQuestions().get(0).getQuestionText());
    }

    @Test
    @DisplayName("a wrapped option is joined onto its own option")
    void joinsWrappedOptionText() {
        String file =
                "Q: Pick the right one?\n"
              + "A) the first choice,\n"
              + "which continues here\n"
              + "B) second\nC) third\nD) fourth\n"
              + "Answer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("the first choice, which continues here", q.getOptionA());
        assertEquals("second", q.getOptionB());
    }

    @Test
    @DisplayName("questions do not need blank lines between them")
    void worksWithoutBlankLines() {
        // Text pulled out of a PDF often has no blank lines at all.
        String file =
                "Q: One?\nA) a\nB) b\nC) c\nD) d\nAnswer: A\n"
              + "Q: Two?\nA) a\nB) b\nC) c\nD) d\nAnswer: B\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(2, result.getQuestions().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    @DisplayName("a title above the first question is ignored")
    void ignoresHeaderBeforeFirstQuestion() {
        String file =
                "PHYSICS 101 - CHAPTER 3 WORKSHEET\n"
              + "Name: ______________    Date: __________\n"
              + "\n"
              + "Q: What is the SI unit of force?\n"
              + "A) Joule\nB) Newton\nC) Watt\nD) Pascal\n"
              + "Answer: B\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(1, result.getQuestions().size());
        assertEquals(0, result.getFailures().size());
        assertEquals("What is the SI unit of force?", result.getQuestions().get(0).getQuestionText());
    }

    // ------------------------------------------------------------------
    // Accepted spellings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keyword and option spellings vary and still parse")
    void acceptsKeywordVariations() {
        String file =
                "q1: Lower case q with a number?\n"
              + "a. alpha\n"
              + "b. beta\n"
              + "c. gamma\n"
              + "d. delta\n"
              + "ans: C\n"
              + "topic: Mixed\n"
              + "difficulty: easy\n"
              + "\n"
              + "QUESTION 2: Upper case with a number?\n"
              + "(A) one\n(B) two\n(C) three\n(D) four\n"
              + "Answer: D\n"
              + "TOPIC: Other\n"
              + "DIFFICULTY: hard\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), result.getFailures().toString());
        List<ParsedQuestion> qs = result.getQuestions();
        assertEquals(2, qs.size());
        assertEquals("Lower case q with a number?", qs.get(0).getQuestionText());
        assertEquals("alpha", qs.get(0).getOptionA());
        assertEquals("C", qs.get(0).getCorrectOption());
        assertEquals("Mixed", qs.get(0).getTopic());
        assertEquals("EASY", qs.get(0).getDifficulty(), "difficulty is upper-cased");
        assertEquals("Upper case with a number?", qs.get(1).getQuestionText());
        assertEquals("four", qs.get(1).getOptionD());
        assertEquals("D", qs.get(1).getCorrectOption());
        assertEquals("HARD", qs.get(1).getDifficulty());
    }

    @Test
    @DisplayName("answer values like 'B)', '(c)' or 'Correct answer is D' are understood")
    void acceptsAnswerVariations() {
        assertEquals("B", answerOf("B)"));
        assertEquals("C", answerOf("(c)"));
        assertEquals("D", answerOf("D."));
        assertEquals("A", answerOf("A - the first one"));
        assertEquals("B", answerOf("Correct answer is B"));
        assertEquals("C", answerOf(" c "));
    }

    private static String answerOf(String answerValue) {
        String file = "Q: Sample?\nA) a\nB) b\nC) c\nD) d\nAnswer: " + answerValue + "\n";
        ImportResult result = QuestionFileParser.parse(file);
        assertEquals(0, result.getFailures().size(),
                "'" + answerValue + "' should be readable as an answer");
        return result.getQuestions().get(0).getCorrectOption();
    }

    @Test
    @DisplayName("a repeated option letter replaces the earlier text")
    void repeatedOptionReplaces() {
        String file = "Q: Sample?\nA) wrong\nA) right\nB) b\nC) c\nD) d\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        assertEquals("right", result.getQuestions().get(0).getOptionA());
    }

    // ------------------------------------------------------------------
    // Skipping, with a reason
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a block missing an option is skipped with the option named")
    void skipsMissingOption() {
        String file =
                "Q: This one has no C?\n"
              + "A) a\nB) b\nD) d\n"
              + "Answer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        ParseFailure failure = result.getFailures().get(0);
        assertEquals(1, failure.getLineNumber());
        assertTrue(failure.getReason().contains("Missing option C"), failure.getReason());
    }

    @Test
    @DisplayName("a block with no Answer line is skipped with a reason")
    void skipsMissingAnswer() {
        String file = "Q: Forgot the answer?\nA) a\nB) b\nC) c\nD) d\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getReason().contains("Missing 'Answer:'"),
                result.getFailures().get(0).getReason());
    }

    @Test
    @DisplayName("an unreadable answer value is skipped with the value quoted")
    void skipsUnreadableAnswer() {
        String file = "Q: Bad answer?\nA) a\nB) b\nC) c\nD) d\nAnswer: yes please\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        String reason = result.getFailures().get(0).getReason();
        assertTrue(reason.contains("yes please"), reason);
        assertTrue(reason.contains("A, B, C or D"), reason);
    }

    @Test
    @DisplayName("a Q: line with no text is skipped")
    void skipsEmptyQuestionText() {
        String file = "Q:\nA) a\nB) b\nC) c\nD) d\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getReason().contains("no question text"),
                result.getFailures().get(0).getReason());
    }

    @Test
    @DisplayName("an option longer than the 500-character column is skipped")
    void skipsOverlongOption() {
        String tooLong = "x".repeat(501);
        String file = "Q: Long option?\nA) " + tooLong + "\nB) b\nC) c\nD) d\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        String reason = result.getFailures().get(0).getReason();
        assertTrue(reason.contains("Option A"), reason);
        assertTrue(reason.contains("501"), reason);
    }

    @Test
    @DisplayName("a topic longer than its column is skipped rather than truncated silently")
    void skipsOverlongTopic() {
        String file = "Q: Sample?\nA) a\nB) b\nC) c\nD) d\nAnswer: A\nTopic: " + "t".repeat(101) + "\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getQuestions().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getReason().contains("Topic"), result.getFailures().get(0).getReason());
    }

    @Test
    @DisplayName("good questions survive alongside bad ones, and failures keep their line numbers")
    void mixesGoodAndBad() {
        String file =
                "Q: Good one?\nA) a\nB) b\nC) c\nD) d\nAnswer: A\n"        // lines 1-6, ok
              + "\n"
              + "Q: Broken one?\nA) a\nB) b\nAnswer: A\n"                  // lines 8-11, missing C and D
              + "\n"
              + "Q: Another good one?\nA) a\nB) b\nC) c\nD) d\nAnswer: D\n"; // lines 13-18, ok

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(2, result.getQuestions().size(), "the two good questions should still import");
        assertEquals(1, result.getFailures().size());
        assertEquals(8, result.getFailures().get(0).getLineNumber(), "failure should point at line 8");
        assertEquals("Good one?", result.getQuestions().get(0).getQuestionText());
        assertEquals("Another good one?", result.getQuestions().get(1).getQuestionText());
    }

    @Test
    @DisplayName("a failure names the question it belongs to")
    void failureCarriesASnippet() {
        String file = "Q: Which gas do plants absorb?\nA) Oxygen\nB) Nitrogen\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getSnippet().contains("Which gas do plants absorb"),
                result.getFailures().get(0).getSnippet());
    }

    // ------------------------------------------------------------------
    // Degenerate input - must never throw
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null, empty and blank input produce an empty result")
    void handlesEmptyInput() {
        for (String input : new String[] { null, "", "   ", "\n\n\n", "\r\n" }) {
            ImportResult result = QuestionFileParser.parse(input);
            assertNotNull(result);
            assertTrue(result.getQuestions().isEmpty(), "no questions expected for " + input);
        }
    }

    @Test
    @DisplayName("a file with no Q: lines explains the problem instead of failing silently")
    void explainsFileWithNoQuestions() {
        String file = "1. What is 2+2?\n   a) 3  b) 4\n   Answer: b\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertTrue(result.getQuestions().isEmpty());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getFailures().get(0).getReason().contains("No 'Q:' lines"),
                result.getFailures().get(0).getReason());
    }

    @Test
    @DisplayName("CRLF line endings, a byte order mark and non-breaking spaces are handled")
    void handlesMessyWhitespace() {
        String file = "\uFEFF"                      // BOM from a Windows text editor
                + "Q:\u00A0What is H2O?\r\n"        // non-breaking space after the keyword
                + "A)\u00A0Water\r\nB) Salt\r\nC) Sugar\r\nD) Acid\r\n"
                + "Answer:\u00A0A\r\n"
                + "Topic:\u00A0Chemistry\r\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), result.getFailures().toString());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("What is H2O?", q.getQuestionText(), "the non-breaking space is normalised away");
        assertEquals("Water", q.getOptionA());
        assertEquals("A", q.getCorrectOption());
        assertEquals("Chemistry", q.getTopic());
        assertEquals("MEDIUM", q.getDifficulty());
    }

    @Test
    @DisplayName("a file with more than the cap imports the first 200 and says so")
    void enforcesQuestionCap() {
        StringBuilder file = new StringBuilder();
        for (int i = 0; i < QuestionFileParser.MAX_QUESTIONS + 25; i++) {
            file.append("Q: Question ").append(i).append("?\n")
                .append("A) a\nB) b\nC) c\nD) d\nAnswer: A\n");
        }

        ImportResult result = QuestionFileParser.parse(file.toString());

        assertEquals(QuestionFileParser.MAX_QUESTIONS, result.getQuestions().size());
        assertFalse(result.getFailures().isEmpty(), "the skipped questions should be reported");
        assertTrue(result.getFailures().stream()
                        .anyMatch(f -> f.getReason().contains("more than " + QuestionFileParser.MAX_QUESTIONS)),
                result.getFailures().toString());
    }

    @Test
    @DisplayName("a stray non-keyword line before any option does not become an option")
    void strayTextAfterQuestionJoinsTheQuestion() {
        String file =
                "Q: Read the passage below.\n"
              + "The passage says the answer is obvious.\n"
              + "A) a\nB) b\nC) c\nD) d\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size());
        assertEquals("Read the passage below. The passage says the answer is obvious.",
                result.getQuestions().get(0).getQuestionText());
    }

    @Test
    @DisplayName("an 'A)' option line is never mistaken for an Answer: line")
    void optionIsNotMistakenForAnswer() {
        String file = "Q: Sample?\nA) Ampere\nB) Becquerel\nC) Coulomb\nD) Dalton\nAnswer: A\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), result.getFailures().toString());
        ParsedQuestion q = result.getQuestions().get(0);
        assertEquals("Ampere", q.getOptionA(), "the option text must not be consumed as the answer");
        assertEquals("A", q.getCorrectOption());
    }

    @Test
    @DisplayName("a Topic line is not swallowed as a continuation of the answer")
    void topicDoesNotBleedIntoAnswer() {
        String file = "Q: Sample?\nA) a\nB) b\nC) c\nD) d\nAnswer: A\nTopic: Biology\n";

        ImportResult result = QuestionFileParser.parse(file);

        assertEquals(0, result.getFailures().size(), result.getFailures().toString());
        assertEquals("A", result.getQuestions().get(0).getCorrectOption());
        assertEquals("Biology", result.getQuestions().get(0).getTopic());
    }
}
