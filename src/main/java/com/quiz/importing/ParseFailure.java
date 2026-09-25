package com.quiz.importing;

/**
 * A block of text in an uploaded file that looked like a question but could not be
 * used, together with the reason and where it came from.
 *
 * <p>These are listed on the import preview screen instead of being dropped
 * silently, so a teacher can see exactly which questions did not make it and why.
 */
public final class ParseFailure {

    private final int lineNumber;
    private final String reason;
    private final String snippet;

    /**
     * @param lineNumber 1-based line the block started on, or 0 when the note is not
     *                   about one specific block (for example the import size cap)
     * @param reason     a short, human-readable explanation
     * @param snippet    a trimmed extract of the offending question, for identification
     */
    public ParseFailure(int lineNumber, String reason, String snippet) {
        this.lineNumber = lineNumber;
        this.reason = reason;
        this.snippet = snippet == null ? "" : snippet;
    }

    public int getLineNumber() { return lineNumber; }
    public String getReason() { return reason; }
    public String getSnippet() { return snippet; }

    @Override
    public String toString() {
        return "ParseFailure{line=" + lineNumber + ", reason=" + reason + "}";
    }
}
