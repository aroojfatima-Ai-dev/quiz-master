package com.quiz.importing;

import com.quiz.validation.InputValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of an uploaded file into questions, using the format documented on
 * the upload screen:
 *
 * <pre>
 * Q: &lt;question text&gt;
 * A) &lt;option A&gt;
 * B) &lt;option B&gt;
 * C) &lt;option C&gt;
 * D) &lt;option D&gt;
 * Answer: &lt;A, B, C, or D&gt;
 * Topic: &lt;topic text&gt;
 * Difficulty: &lt;EASY, MEDIUM, or HARD&gt;
 * </pre>
 *
 * <p><b>Only the {@code Q:} line separates questions.</b> Blank lines between
 * questions are the documented convention, but they are not required, because text
 * pulled out of a PDF or DOCX does not reliably keep them. Questions come back in
 * exactly the order they appear in the file.
 *
 * <p><b>Wrapped lines are kept.</b> PDF and DOCX extraction breaks long lines
 * wherever the page happens to end, so any line that does not start with a known
 * keyword is treated as a continuation of the field above it. That is what makes
 * "Q: What is the capital" / "of France?" parse as one question.
 *
 * <p><b>Topic and Difficulty are optional</b> and fall back to {@code General} and
 * {@code MEDIUM}. They are also matched case-insensitively, and an unrecognised
 * difficulty (say {@code "Difficult"}) falls back to {@code MEDIUM} rather than
 * throwing the question away - neither value can be rejected by the database once
 * normalised.
 *
 * <p>Everything else is a hard requirement: a block with no question text, a missing
 * option, an option longer than the {@code VARCHAR(500)} column, or a missing or
 * unreadable answer is reported as a {@link ParseFailure} with a reason and the line
 * it started on, and is skipped.
 *
 * <p>Tolerance is limited on purpose. Accepted keyword spellings are {@code Q:},
 * {@code Q1:}, {@code Question 2:}, {@code Answer:}, {@code Ans:}, {@code Topic:} and
 * {@code Difficulty:}, each with {@code :}, {@code )} or {@code .} as the separator;
 * options may be written {@code A)}, {@code A.} or {@code A:} in either case. The
 * parser never throws: malformed input becomes failures, not exceptions.
 */
public final class QuestionFileParser {

    /**
     * Upper bound on how many questions one file may contribute. Guards the UI, which
     * builds an editable card per question, against a file that accidentally contains
     * thousands of blocks. Anything past this is reported as a skipped entry.
     */
    public static final int MAX_QUESTIONS = 200;

    /** The block shown to teachers as instructions, and used in the tests. */
    public static final String DOCUMENTED_FORMAT =
            "Q: <question text>\n"
          + "A) <option A>\n"
          + "B) <option B>\n"
          + "C) <option C>\n"
          + "D) <option D>\n"
          + "Answer: <A, B, C, or D>\n"
          + "Topic: <topic text>\n"
          + "Difficulty: <EASY, MEDIUM, or HARD>";

    private static final Pattern QUESTION_LINE =
            Pattern.compile("^\\s*Q(?:uestion)?\\s*\\d*\\s*[:.)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /** {@code Answer:} / {@code Ans:}, with an optional separator so "Answer A" works. */
    private static final Pattern ANSWER_LINE =
            Pattern.compile("^\\s*(?:Answer|Ans)\\b\\s*[:.)]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOPIC_LINE =
            Pattern.compile("^\\s*Topic\\b\\s*[:.)]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIFFICULTY_LINE =
            Pattern.compile("^\\s*Difficulty\\b\\s*[:.)]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * An option line: a single letter A-D followed by a separator. The opening
     * bracket is optional and spacing inside the brackets is allowed, so {@code "A)"},
     * {@code "A."}, {@code "A:"} and {@code "(A)"} are all accepted.
     */
    private static final Pattern OPTION_LINE =
            Pattern.compile("^\\s*\\(?\\s*([A-Da-d])\\s*[).:]\\s*(.*)$");

    private static final String[] OPTION_LETTERS = { "A", "B", "C", "D" };

    private static final String DEFAULT_TOPIC = "General";
    private static final String DEFAULT_DIFFICULTY = "MEDIUM";

    private QuestionFileParser() {
        // Utility class - not instantiable.
    }

    /**
     * Parses the text of a questions file.
     *
     * @param rawText the whole file, already extracted from the PDF/DOCX/TXT; may be
     *                null or blank, in which case the result is simply empty
     * @return the questions that parsed plus the blocks that were skipped; never null
     */
    public static ImportResult parse(String rawText) {
        List<ParsedQuestion> parsed = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return new ImportResult(parsed, failures);
        }

        String text = normalise(rawText);
        String[] lines = text.split("\n", -1);

        Block current = null;
        boolean capReported = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i].strip();

            // Blank lines only ever separate blocks, so they carry no meaning here.
            if (line.isEmpty()) {
                continue;
            }

            Matcher question = QUESTION_LINE.matcher(line);
            if (question.matches()) {
                // A new Q: line closes the block that was being built.
                if (current != null) {
                    if (parsed.size() >= MAX_QUESTIONS) {
                        capReported = true;
                    } else {
                        finish(current, parsed, failures);
                    }
                }
                current = new Block(lineNumber);
                current.question.append(question.group(1).strip());
                current.lastField = Field.QUESTION;
                continue;
            }

            // Text before the first Q: line is a title or a header, not a question.
            if (current == null) {
                continue;
            }

            // Keyword lines are checked before the option pattern on purpose: keyword
            // matching is precise (word boundaries), so it can never steal an option,
            // while the reverse would need the option pattern to be just as precise.
            Matcher matcher;
            if ((matcher = ANSWER_LINE.matcher(line)).matches()) {
                current.answer.append(joined(current.answer, matcher.group(1).strip()));
                current.lastField = Field.ANSWER;
            } else if ((matcher = TOPIC_LINE.matcher(line)).matches()) {
                current.topic.append(joined(current.topic, matcher.group(1).strip()));
                current.lastField = Field.TOPIC;
            } else if ((matcher = DIFFICULTY_LINE.matcher(line)).matches()) {
                current.difficulty.append(joined(current.difficulty, matcher.group(1).strip()));
                current.lastField = Field.DIFFICULTY;
            } else if ((matcher = OPTION_LINE.matcher(line)).matches()) {
                int index = Character.toUpperCase(matcher.group(1).charAt(0)) - 'A';
                StringBuilder option = current.options[index];
                // A repeated option letter replaces the earlier text rather than
                // appending to it, so a duplicate line does not corrupt the value.
                option.setLength(0);
                option.append(matcher.group(2).strip());
                current.lastField = Field.option(index + 1);
            } else {
                // Not a keyword: this is a wrapped line belonging to the field above.
                appendToLastField(current, line);
            }
        }

        if (current != null) {
            if (parsed.size() >= MAX_QUESTIONS) {
                capReported = true;
            } else {
                finish(current, parsed, failures);
            }
        }

        if (capReported) {
            failures.add(new ParseFailure(0,
                    "This file contains more than " + MAX_QUESTIONS + " questions; only the first "
                            + MAX_QUESTIONS + " were imported. Split the file and upload the rest separately.",
                    ""));
        }

        // A file with content but not a single Q: line almost always means the teacher
        // pasted their questions in a different shape. Saying so is far more useful
        // than reporting "no questions found".
        if (parsed.isEmpty() && failures.isEmpty()) {
            failures.add(new ParseFailure(1,
                    "No 'Q:' lines were found, so nothing in this file looks like a question block. "
                            + "Compare it with the format example above.",
                    firstMeaningfulLine(text)));
        }

        return new ImportResult(parsed, failures);
    }

    // ------------------------------------------------------------------
    // Block accumulation
    // ------------------------------------------------------------------

    /** Which field a continuation line should be appended to. */
    private enum Field {
        NONE, QUESTION, A, B, C, D, ANSWER, TOPIC, DIFFICULTY;

        static Field option(int oneBasedIndex) {
            switch (oneBasedIndex) {
                case 1: return A;
                case 2: return B;
                case 3: return C;
                default: return D;
            }
        }
    }

    /** One question being collected: starts at a Q: line, ends at the next one. */
    private static final class Block {
        final int startLine;
        final StringBuilder question = new StringBuilder();
        final StringBuilder[] options = {
                new StringBuilder(), new StringBuilder(), new StringBuilder(), new StringBuilder()
        };
        final StringBuilder answer = new StringBuilder();
        final StringBuilder topic = new StringBuilder();
        final StringBuilder difficulty = new StringBuilder();
        Field lastField = Field.NONE;

        Block(int startLine) {
            this.startLine = startLine;
        }
    }

    private static void appendToLastField(Block block, String line) {
        switch (block.lastField) {
            case QUESTION:   block.question.append(joined(block.question, line)); break;
            case A:          block.options[0].append(joined(block.options[0], line)); break;
            case B:          block.options[1].append(joined(block.options[1], line)); break;
            case C:          block.options[2].append(joined(block.options[2], line)); break;
            case D:          block.options[3].append(joined(block.options[3], line)); break;
            case ANSWER:     block.answer.append(joined(block.answer, line)); break;
            case TOPIC:      block.topic.append(joined(block.topic, line)); break;
            case DIFFICULTY: block.difficulty.append(joined(block.difficulty, line)); break;
            default:         break; // Nothing collected yet - ignore the line.
        }
    }

    /** Joins a continuation onto the existing text without doubling up separators. */
    private static String joined(StringBuilder existing, String extra) {
        if (existing.length() == 0) {
            return extra;
        }
        return " " + extra;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** Validates a finished block and files it as either a question or a failure. */
    private static void finish(Block block, List<ParsedQuestion> parsed, List<ParseFailure> failures) {
        String questionText = block.question.toString().strip();
        String snippet = snippetOf(questionText);

        if (questionText.isEmpty()) {
            failures.add(new ParseFailure(block.startLine,
                    "The 'Q:' line has no question text after it.", snippet));
            return;
        }

        String[] options = new String[4];
        for (int i = 0; i < 4; i++) {
            options[i] = block.options[i].toString().strip();
            if (options[i].isEmpty()) {
                failures.add(new ParseFailure(block.startLine,
                        "Missing option " + OPTION_LETTERS[i]
                                + " (expected a line starting with \"" + OPTION_LETTERS[i] + ")\").",
                        snippet));
                return;
            }
            if (options[i].length() > InputValidator.OPTION_MAX_LENGTH) {
                failures.add(new ParseFailure(block.startLine,
                        "Option " + OPTION_LETTERS[i] + " is " + options[i].length()
                                + " characters long; the limit is "
                                + InputValidator.OPTION_MAX_LENGTH + ".", snippet));
                return;
            }
        }

        String correctOption = extractAnswerLetter(block.answer.toString());
        if (correctOption == null) {
            failures.add(new ParseFailure(block.startLine,
                    block.answer.length() == 0
                            ? "Missing 'Answer:' line (expected \"Answer: A\", \"Answer: B\", \"Answer: C\" or \"Answer: D\")."
                            : "Could not read \"" + truncate(block.answer.toString().strip(), 30)
                                    + "\" as an answer; expected A, B, C or D.",
                    snippet));
            return;
        }

        String topic = block.topic.toString().strip();
        if (topic.isEmpty()) {
            topic = DEFAULT_TOPIC;
        } else if (topic.length() > InputValidator.TOPIC_MAX_LENGTH) {
            failures.add(new ParseFailure(block.startLine,
                    "Topic is " + topic.length() + " characters long; the limit is "
                            + InputValidator.TOPIC_MAX_LENGTH + ".", snippet));
            return;
        }

        parsed.add(new ParsedQuestion(questionText, options[0], options[1], options[2], options[3],
                correctOption, topic, normaliseDifficulty(block.difficulty.toString())));
    }

    /**
     * Pulls the answer letter out of the text after {@code Answer:}.
     *
     * <p>Handles the documented {@code "A"} as well as the shapes teachers actually
     * write: {@code "A)"}, {@code "A."}, {@code "(c)"} and even {@code "Correct answer is B"}.
     *
     * @return "A".."D", or null when no unambiguous letter is present
     */
    private static String extractAnswerLetter(String rawAnswer) {
        if (rawAnswer == null) {
            return null;
        }
        String trimmed = rawAnswer.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] tokens = trimmed.split("[\\s,;/]+");
        for (String token : tokens) {
            String bare = stripPunctuation(token);
            if (bare.length() == 1 && "ABCDabcd".indexOf(bare.charAt(0)) >= 0) {
                return bare.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** Normalises a difficulty, falling back to MEDIUM for anything unrecognised. */
    private static String normaliseDifficulty(String rawDifficulty) {
        String value = rawDifficulty == null ? "" : rawDifficulty.strip().toUpperCase(Locale.ROOT);
        switch (value) {
            case "EASY":
            case "MEDIUM":
            case "HARD":
                return value;
            default:
                return DEFAULT_DIFFICULTY;
        }
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    /**
     * Puts every line break on {@code \n} and removes the characters a PDF or Word
     * file leaves behind that would otherwise break the line-anchored patterns:
     * a leading byte order mark, non-breaking spaces, and the Unicode line/paragraph
     * separators.
     */
    private static String normalise(String rawText) {
        String text = rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')   // non-breaking space
                .replace('\u2028', '\n')  // line separator
                .replace('\u2029', '\n'); // paragraph separator

        // A UTF-8 BOM saved by a Windows editor would otherwise sit in front of the
        // first "Q:" and stop the very first question from matching.
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }

    private static String stripPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && !Character.isLetterOrDigit(token.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(token.charAt(end - 1))) {
            end--;
        }
        return token.substring(start, end);
    }

    private static String snippetOf(String questionText) {
        if (questionText == null || questionText.isEmpty()) {
            return "(no question text)";
        }
        return "\"" + truncate(questionText, 70) + "\"";
    }

    private static String firstMeaningfulLine(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return "\"" + truncate(trimmed, 70) + "\"";
            }
        }
        return "";
    }

    private static String truncate(String value, int maxLength) {
        String trimmed = value.strip();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "\u2026";
    }
}
