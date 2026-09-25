package com.quiz.importing;

/**
 * One question that was read successfully from an uploaded file.
 *
 * <p>Immutable, and deliberately free of any JavaFX dependency: a file gives plain
 * data, so the parser can be unit tested without starting a window. {@code App}
 * copies each of these into its own mutable {@code DraftQuestion} for the preview
 * screen.
 */
public final class ParsedQuestion {

    private final String questionText;
    private final String optionA;
    private final String optionB;
    private final String optionC;
    private final String optionD;
    private final String correctOption;
    private final String topic;
    private final String difficulty;

    public ParsedQuestion(String questionText, String optionA, String optionB,
                          String optionC, String optionD, String correctOption,
                          String topic, String difficulty) {
        this.questionText = questionText;
        this.optionA = optionA;
        this.optionB = optionB;
        this.optionC = optionC;
        this.optionD = optionD;
        this.correctOption = correctOption;
        this.topic = topic;
        this.difficulty = difficulty;
    }

    public String getQuestionText() { return questionText; }
    public String getOptionA() { return optionA; }
    public String getOptionB() { return optionB; }
    public String getOptionC() { return optionC; }
    public String getOptionD() { return optionD; }
    public String getCorrectOption() { return correctOption; }
    public String getTopic() { return topic; }
    public String getDifficulty() { return difficulty; }

    @Override
    public String toString() {
        return "ParsedQuestion{correct=" + correctOption + ", topic=" + topic
                + ", difficulty=" + difficulty + "}";
    }
}
