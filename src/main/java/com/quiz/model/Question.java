package com.quiz.model;

/**
 * An immutable multiple-choice question as persisted in the {@code questions} table.
 *
 * <p>This is the persistence counterpart of {@code App.DraftQuestion}: the draft is
 * mutable while a teacher is editing it, and is converted into a {@code Question}
 * when the test is saved. Keeping the DAO layer free of the UI's draft type is what
 * allows a whole test to be written in a single transaction.
 */
public class Question {

    private final String questionText;
    private final String optionA;
    private final String optionB;
    private final String optionC;
    private final String optionD;
    private final String correctOption;
    private final String topic;
    private final String difficulty;

    public Question(String questionText, String optionA, String optionB, String optionC,
                    String optionD, String correctOption, String topic, String difficulty) {
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
        return "Question{correct=" + correctOption + ", topic=" + topic
                + ", difficulty=" + difficulty + "}";
    }
}
