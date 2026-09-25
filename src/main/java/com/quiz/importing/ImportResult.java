package com.quiz.importing;

import java.util.Collections;
import java.util.List;

/**
 * Everything {@link QuestionFileParser} got out of one uploaded file: the questions
 * that parsed, in the order they appeared, and the blocks that were skipped.
 */
public final class ImportResult {

    private final List<ParsedQuestion> questions;
    private final List<ParseFailure> failures;

    public ImportResult(List<ParsedQuestion> questions, List<ParseFailure> failures) {
        this.questions = Collections.unmodifiableList(questions);
        this.failures = Collections.unmodifiableList(failures);
    }

    /** Questions that parsed successfully, in file order. */
    public List<ParsedQuestion> getQuestions() { return questions; }

    /** Blocks that were skipped, each with a reason. */
    public List<ParseFailure> getFailures() { return failures; }

    public boolean isEmpty() { return questions.isEmpty() && failures.isEmpty(); }
}
