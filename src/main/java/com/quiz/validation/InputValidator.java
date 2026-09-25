package com.quiz.validation;

import com.quiz.model.Question;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Input validation rules for QuizMaster, extracted out of {@code App} so they can
 * be unit tested without starting JavaFX.
 *
 * <p>Every {@code validateXxx} method returns {@code null} when the input is
 * acceptable, or a human-readable reason to show the user. That convention matches
 * the original {@code App.validateInput} signature.
 *
 * <p>Field length limits are not arbitrary: they mirror the column widths declared
 * in {@code schema.sql}. Without them, an over-long value reached MySQL and came
 * back as a raw "Data too long for column ..." {@code SQLException}, which the UI
 * displayed as an unexplained "Database Error".
 */
public final class InputValidator {

    /** Letters, digits, underscore and dot; 3 to 50 characters (users.username is VARCHAR(50)). */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_.]{3,50}$");

    /**
     * Practical e-mail shape: no leading/trailing or repeated dots in the local
     * part, domain labels that neither start nor end with a hyphen, and a 2-63
     * character top-level domain.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(?!.*\\.\\.)[A-Za-z0-9+_-](?:[A-Za-z0-9+_.-]*[A-Za-z0-9+_-])?"
                    + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");

    /** Exactly six letters/digits, used for class join codes. */
    private static final Pattern CLASS_CODE_PATTERN =
            Pattern.compile("^[A-Z0-9]{6}$");

    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 50;
    public static final int EMAIL_MAX_LENGTH = 120;
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 128;
    public static final int CLASS_NAME_MAX_LENGTH = 100;
    public static final int CLASS_CODE_LENGTH = 6;
    public static final int TEST_TITLE_MAX_LENGTH = 200;
    public static final int OPTION_MAX_LENGTH = 500;
    public static final int TOPIC_MAX_LENGTH = 100;

    /**
     * Upper bound for the text of one question.
     *
     * <p>The column is a {@code TEXT} (about 64 KB), which is far more than any real
     * multiple-choice question needs; a paste or an imported file that exceeds this is a
     * mistake, and catching it here produces a readable message instead of MySQL's
     * "Data too long for column 'question_text'".
     */
    public static final int QUESTION_TEXT_MAX_LENGTH = 2000;

    /**
     * Upper bound for a test duration, in minutes (1,440 = 24 hours).
     *
     * <p>The UI stores durations as {@code minutes * 60} seconds in an {@code int}, so
     * an unbounded field let a large value overflow to a negative number of seconds and
     * produce a test whose timer expired immediately.
     */
    public static final int MAX_TEST_MINUTES = 1440;

    private InputValidator() {
        // Utility class - not instantiable.
    }

    // -----------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------

    /**
     * Validates the registration form.
     *
     * @return null when the account may be created, otherwise the message to display
     */
    public static String validateRegistration(String username, String email,
                                              String password, String confirmPassword) {
        if (isBlank(username) || isBlank(email) || isBlank(password) || isBlank(confirmPassword)) {
            return "All fields are required.";
        }

        String trimmedUsername = username.trim();
        if (trimmedUsername.length() > USERNAME_MAX_LENGTH) {
            return "Username must be at most " + USERNAME_MAX_LENGTH + " characters.";
        }
        if (!USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
            return "Username must be " + USERNAME_MIN_LENGTH + "-" + USERNAME_MAX_LENGTH
                    + " characters (letters, numbers, underscore, dot).";
        }

        String trimmedEmail = email.trim();
        if (trimmedEmail.length() > EMAIL_MAX_LENGTH) {
            return "Email must be at most " + EMAIL_MAX_LENGTH + " characters.";
        }
        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            return "Please enter a valid email address.";
        }

        if (password.length() < PASSWORD_MIN_LENGTH) {
            return "Password must be at least " + PASSWORD_MIN_LENGTH + " characters.";
        }
        if (password.length() > PASSWORD_MAX_LENGTH) {
            return "Password must be at most " + PASSWORD_MAX_LENGTH + " characters.";
        }
        if (!password.equals(confirmPassword)) {
            return "Passwords do not match.";
        }
        return null;
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username.trim()).matches();
    }

    public static boolean isValidEmail(String email) {
        return email != null
                && email.trim().length() <= EMAIL_MAX_LENGTH
                && EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null
                && password.length() >= PASSWORD_MIN_LENGTH
                && password.length() <= PASSWORD_MAX_LENGTH;
    }

    // -----------------------------------------------------------------
    // Classes
    // -----------------------------------------------------------------

    /**
     * Validates the "create class" form.
     *
     * @param classCode the raw code typed by the teacher; it is normalised with
     *                  {@link #normalizeClassCode(String)} before being checked
     * @return null when the class may be created, otherwise the message to display
     */
    public static String validateClass(String className, String classCode) {
        if (isBlank(className) || isBlank(classCode)) {
            return "Please enter both Class Name and Class Code.";
        }
        if (className.trim().length() > CLASS_NAME_MAX_LENGTH) {
            return "Class name must be at most " + CLASS_NAME_MAX_LENGTH + " characters.";
        }
        String normalized = normalizeClassCode(classCode);
        if (!CLASS_CODE_PATTERN.matcher(normalized).matches()) {
            return "Class code must be exactly " + CLASS_CODE_LENGTH
                    + " alphanumeric characters (letters and numbers only).";
        }
        return null;
    }

    /**
     * Validates a class code a student typed in order to join a class.
     *
     * @return null when the code is usable, otherwise the message to display
     */
    public static String validateJoinClass(String teacherUsername, String classCode) {
        if (isBlank(teacherUsername) || isBlank(classCode)) {
            return "Please enter both teacher username and class code.";
        }
        if (!CLASS_CODE_PATTERN.matcher(normalizeClassCode(classCode)).matches()) {
            return "Class code must be exactly " + CLASS_CODE_LENGTH
                    + " alphanumeric characters (letters and numbers only).";
        }
        return null;
    }

    /**
     * Trims and upper-cases a class code using a locale-independent mapping.
     *
     * <p>{@code String.toUpperCase()} without an explicit locale follows the default
     * locale, so on a Turkish system the code {@code "phy10i"} became {@code "PHY10İ"}
     * (dotted capital I). That failed the alphanumeric check and, worse, could be
     * stored as a different code than the one a student later typed. Codes are pure
     * ASCII, so {@link Locale#ROOT} is the correct mapping.
     */
    public static String normalizeClassCode(String classCode) {
        return classCode == null ? "" : classCode.trim().toUpperCase(Locale.ROOT);
    }

    // -----------------------------------------------------------------
    // Test / question authoring
    // -----------------------------------------------------------------

    /**
     * Validates a test title.
     *
     * @return null when acceptable, otherwise the message to display
     */
    public static String validateTestTitle(String title) {
        if (isBlank(title)) {
            return "Please enter a test title.";
        }
        if (title.trim().length() > TEST_TITLE_MAX_LENGTH) {
            return "Test title must be at most " + TEST_TITLE_MAX_LENGTH + " characters.";
        }
        return null;
    }

    /**
     * Validates one multiple-choice question.
     *
     * <p>All four options are required. The "Finish &amp; Save Test" button used to
     * check only options A and B, so a question with blank options C and D could be
     * persisted and then shown to students as two empty radio buttons.
     *
     * @return null when acceptable, otherwise the message to display
     */
    public static String validateQuestion(String questionText, String optionA, String optionB,
                                          String optionC, String optionD) {
        if (isBlank(questionText) || isBlank(optionA) || isBlank(optionB)
                || isBlank(optionC) || isBlank(optionD)) {
            return "Please enter question text and all 4 options before proceeding.";
        }
        if (questionText.trim().length() > QUESTION_TEXT_MAX_LENGTH) {
            return "Question text must be at most " + QUESTION_TEXT_MAX_LENGTH + " characters.";
        }
        if (anyTooLong(optionA, optionB, optionC, optionD)) {
            return "Each option must be at most " + OPTION_MAX_LENGTH + " characters.";
        }
        return null;
    }

    /**
     * Validates one question as it is about to be written to the database.
     *
     * <p>Same rules as {@link #validateQuestion(String, String, String, String, String)},
     * expressed over the persistence type so the write path itself can enforce them. The
     * UI validates every question before saving, but a question that slipped through with
     * a blank option used to be stored without complaint - MySQL accepts an empty string
     * in a {@code NOT NULL} column - and the student then saw empty radio buttons.
     *
     * @param question the question to check; may be null
     * @return null when acceptable, otherwise the message to display
     */
    public static String validateQuestion(Question question) {
        if (question == null) {
            return "Please enter question text and all 4 options before proceeding.";
        }
        String error = validateQuestion(question.getQuestionText(), question.getOptionA(),
                question.getOptionB(), question.getOptionC(), question.getOptionD());
        if (error == null) {
            error = validateTopic(question.getTopic());
        }
        return error;
    }

    /**
     * Validates a topic string, which is optional and falls back to "General".
     *
     * @return null when acceptable, otherwise the message to display
     */
    public static String validateTopic(String topic) {
        if (topic != null && topic.trim().length() > TOPIC_MAX_LENGTH) {
            return "Topic must be at most " + TOPIC_MAX_LENGTH + " characters.";
        }
        return null;
    }

    /**
     * Parses a positive integer from a text field.
     *
     * @return the parsed value, or -1 when the text is not a positive integer
     *         (including values that overflow {@code int})
     */
    public static int parsePositiveInt(String text) {
        if (isBlank(text)) {
            return -1;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Formats a duration for display without losing the seconds.
     *
     * <p>The UI used to compute {@code totalSeconds / 60}, so a 90-second test was
     * shown as "1 mins" and a 30-second test as "0 mins".
     */
    public static String formatDuration(int totalSeconds) {
        if (totalSeconds <= 0) {
            return "0 mins";
        }
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (seconds == 0) {
            return minutes + (minutes == 1 ? " min" : " mins");
        }
        return minutes + "m " + seconds + "s";
    }

    private static boolean anyTooLong(String... values) {
        for (String value : values) {
            if (value != null && value.length() > OPTION_MAX_LENGTH) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
