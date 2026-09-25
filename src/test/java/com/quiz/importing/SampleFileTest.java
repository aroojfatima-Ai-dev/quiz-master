package com.quiz.importing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the sample question file that ships in {@code samples/}.
 *
 * <p>A sample that does not itself parse would be worse than no sample at all: it is the
 * first thing a teacher will copy. The file also exercises the optional fields, an answer
 * written as text and a decorated difficulty, so it doubles as a smoke test of the reader
 * and the parser working together on a real file on disk.
 */
class SampleFileTest {

    private static final Path SAMPLE = Path.of("samples", "sample-questions.txt");

    @Test
    @DisplayName("samples/sample-questions.txt is a valid question file")
    void sampleFileParses() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "the sample file is part of the repository");

        String text = QuestionFileReader.readText(SAMPLE.toFile());
        ImportResult result = QuestionFileParser.parse(text);

        assertEquals(0, result.getFailures().size(),
                "every question in the sample must be readable: " + result.getFailures());
        assertEquals(5, result.getQuestions().size());

        ParsedQuestion first = result.getQuestions().get(0);
        assertEquals("What is the capital of France?", first.getQuestionText());
        assertEquals("B", first.getCorrectOption());
        assertEquals("Geography", first.getTopic());
        assertEquals("EASY", first.getDifficulty());

        assertEquals("C", result.getQuestions().get(4).getCorrectOption(),
                "file order is preserved to the last question");

        // The third question writes its answer as text rather than a letter, and the
        // fourth decorates its difficulty; both are documented as tolerated.
        assertEquals("B", result.getQuestions().get(2).getCorrectOption(),
                "'Answer: Queue' should be matched to option B");
        assertEquals("HARD", result.getQuestions().get(3).getDifficulty(),
                "'Hard (level 3)' should be read as HARD");
        assertTrue(result.getQuestions().stream()
                        .allMatch(q -> q.getCorrectOption().matches("[A-D]")),
                "every parsed question carries an A-D answer");
    }
}
