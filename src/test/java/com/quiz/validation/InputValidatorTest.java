package com.quiz.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InputValidator}.
 *
 * <p>These cover the rules that used to live inside the JavaFX {@code App} class,
 * where they could not be exercised without launching a window and clicking through
 * the registration form.
 */
class InputValidatorTest {

    private static final String OK_PASSWORD = "password123";

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a complete, well-formed registration is accepted")
    void acceptsValidRegistration() {
        assertNull(InputValidator.validateRegistration("ali_khan", "ali@example.com",
                OK_PASSWORD, OK_PASSWORD));
        assertNull(InputValidator.validateRegistration("abc", "a.b+c@sub.example.co.uk",
                "exactly8", "exactly8"));
    }

    @Test
    @DisplayName("missing fields are rejected before any pattern is applied")
    void rejectsMissingFields() {
        String expected = "All fields are required.";
        assertEquals(expected, InputValidator.validateRegistration(null, "a@b.com", OK_PASSWORD, OK_PASSWORD));
        assertEquals(expected, InputValidator.validateRegistration("ali", "", OK_PASSWORD, OK_PASSWORD));
        assertEquals(expected, InputValidator.validateRegistration("ali", "a@b.com", "   ", OK_PASSWORD));
        assertEquals(expected, InputValidator.validateRegistration("ali", "a@b.com", OK_PASSWORD, null));
    }

    @Test
    @DisplayName("usernames must be 3-50 characters of letters, digits, underscore or dot")
    void enforcesUsernameRules() {
        assertNotNull(InputValidator.validateRegistration("ab", "a@b.com", OK_PASSWORD, OK_PASSWORD),
                "two characters is below the minimum");
        assertNull(InputValidator.validateRegistration("abc", "a@b.com", OK_PASSWORD, OK_PASSWORD));

        assertNotNull(InputValidator.validateRegistration("has space", "a@b.com", OK_PASSWORD, OK_PASSWORD));
        assertNotNull(InputValidator.validateRegistration("bad@char", "a@b.com", OK_PASSWORD, OK_PASSWORD));
        assertNotNull(InputValidator.validateRegistration("bad-char", "a@b.com", OK_PASSWORD, OK_PASSWORD));

        // 50 characters is the width of users.username; longer values used to reach
        // MySQL and come back as an unexplained "Data too long" database error.
        String exactly50 = "a".repeat(InputValidator.USERNAME_MAX_LENGTH);
        String tooLong = "a".repeat(InputValidator.USERNAME_MAX_LENGTH + 1);
        assertNull(InputValidator.validateRegistration(exactly50, "a@b.com", OK_PASSWORD, OK_PASSWORD));
        assertNotNull(InputValidator.validateRegistration(tooLong, "a@b.com", OK_PASSWORD, OK_PASSWORD));
    }

    @Test
    @DisplayName("e-mail addresses must have a plausible shape")
    void enforcesEmailRules() {
        assertTrue(InputValidator.isValidEmail("ali@example.com"));
        assertTrue(InputValidator.isValidEmail("first.last+tag@sub.domain.co.uk"));
        assertTrue(InputValidator.isValidEmail("a@b.io"));

        assertFalse(InputValidator.isValidEmail("no-at-sign"));
        assertFalse(InputValidator.isValidEmail("@example.com"));
        assertFalse(InputValidator.isValidEmail("ali@"));
        assertFalse(InputValidator.isValidEmail("ali@example"));
        assertFalse(InputValidator.isValidEmail("ali@.com"));
        assertFalse(InputValidator.isValidEmail("ali..khan@example.com"), "repeated dots");
        assertFalse(InputValidator.isValidEmail(".ali@example.com"), "leading dot");
        assertFalse(InputValidator.isValidEmail("ali.@example.com"), "trailing dot");
        assertFalse(InputValidator.isValidEmail("ali@-example.com"), "label starts with a hyphen");
        assertFalse(InputValidator.isValidEmail("ali@example-.com"), "label ends with a hyphen");
        assertFalse(InputValidator.isValidEmail("ali@example.c"), "single-character TLD");
        assertFalse(InputValidator.isValidEmail(null));

        String tooLong = "a".repeat(InputValidator.EMAIL_MAX_LENGTH - 10) + "@example.com";
        assertFalse(InputValidator.isValidEmail(tooLong), "exceeds users.email width");
    }

    @Test
    @DisplayName("passwords need a minimum length and must be confirmed exactly")
    void enforcesPasswordRules() {
        String tooShort = "a".repeat(InputValidator.PASSWORD_MIN_LENGTH - 1);
        assertEquals("Password must be at least " + InputValidator.PASSWORD_MIN_LENGTH + " characters.",
                InputValidator.validateRegistration("ali", "a@b.com", tooShort, tooShort));

        String exactlyMin = "a".repeat(InputValidator.PASSWORD_MIN_LENGTH);
        assertNull(InputValidator.validateRegistration("ali", "a@b.com", exactlyMin, exactlyMin));

        String tooLong = "a".repeat(InputValidator.PASSWORD_MAX_LENGTH + 1);
        assertNotNull(InputValidator.validateRegistration("ali", "a@b.com", tooLong, tooLong));

        assertEquals("Passwords do not match.",
                InputValidator.validateRegistration("ali", "a@b.com", OK_PASSWORD, "password124"));
    }

    @Test
    @DisplayName("the e-mail and username are trimmed but the password is not")
    void trimsIdentifiersOnly() {
        assertNull(InputValidator.validateRegistration("  ali_khan  ", "  ali@example.com  ",
                OK_PASSWORD, OK_PASSWORD));
        // A password is literal: leading and trailing spaces are part of it.
        assertEquals("Passwords do not match.",
                InputValidator.validateRegistration("ali", "a@b.com", OK_PASSWORD, " " + OK_PASSWORD));
    }

    // ------------------------------------------------------------------
    // Class codes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a six-character alphanumeric class code is accepted in any case")
    void enforcesClassCodeRules() {
        assertNull(InputValidator.validateClass("Physics 101", "PHY101"));
        assertNull(InputValidator.validateClass("Physics 101", "phy101"));
        assertNull(InputValidator.validateClass("Physics 101", " k9x2p4 "));

        assertNotNull(InputValidator.validateClass("Physics 101", "PHY10"));
        assertNotNull(InputValidator.validateClass("Physics 101", "PHY1011"));
        assertNotNull(InputValidator.validateClass("Physics 101", "PHY-01"));
        assertNotNull(InputValidator.validateClass("Physics 101", "PHY 01"));
        assertNotNull(InputValidator.validateClass("", "PHY101"));
        assertNotNull(InputValidator.validateClass("Physics 101", ""));
        assertNotNull(InputValidator.validateClass(null, null));
    }

    @Test
    @DisplayName("class name length is capped at the width of classes.class_name")
    void enforcesClassNameLength() {
        String exactly100 = "c".repeat(InputValidator.CLASS_NAME_MAX_LENGTH);
        assertNull(InputValidator.validateClass(exactly100, "PHY101"));
        assertNotNull(InputValidator.validateClass(exactly100 + "x", "PHY101"));
    }

    @Test
    @DisplayName("normalising a class code does not depend on the default locale")
    void classCodeNormalisationIsLocaleIndependent() {
        // Regression test: toUpperCase() without a locale followed the default
        // locale, so on a Turkish system the 'i' in "phy10i" became a dotted capital
        // I (U+0130). That failed the alphanumeric check and could be stored as a
        // different code than the one a student later typed.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("PHY10I", InputValidator.normalizeClassCode(" phy10i "));
            assertNull(InputValidator.validateClass("Physics 101", "phy10i"));
            assertNull(InputValidator.validateJoinClass("prof_smith", "phy10i"));
        } finally {
            Locale.setDefault(original);
        }

        assertEquals("", InputValidator.normalizeClassCode(null));
        assertEquals("ABC123", InputValidator.normalizeClassCode("abc123"));
    }

    @Test
    @DisplayName("joining a class requires a teacher username and a valid code")
    void enforcesJoinClassRules() {
        assertNull(InputValidator.validateJoinClass("prof_smith", "K9X2P4"));
        assertNotNull(InputValidator.validateJoinClass("", "K9X2P4"));
        assertNotNull(InputValidator.validateJoinClass("prof_smith", ""));
        assertNotNull(InputValidator.validateJoinClass("prof_smith", "K9X2P"));
        assertNotNull(InputValidator.validateJoinClass(null, null));
    }

    // ------------------------------------------------------------------
    // Test authoring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a test title is required and capped at tests.title width")
    void enforcesTestTitleRules() {
        assertNull(InputValidator.validateTestTitle("Java Basics Quiz"));
        assertNotNull(InputValidator.validateTestTitle(""));
        assertNotNull(InputValidator.validateTestTitle("   "));
        assertNotNull(InputValidator.validateTestTitle(null));

        String exactly200 = "t".repeat(InputValidator.TEST_TITLE_MAX_LENGTH);
        assertNull(InputValidator.validateTestTitle(exactly200));
        assertNotNull(InputValidator.validateTestTitle(exactly200 + "x"));
    }

    @Test
    @DisplayName("all four options are required for a question")
    void enforcesQuestionRules() {
        assertNull(InputValidator.validateQuestion("What is 2+2?", "3", "4", "5", "6"));

        // The "Finish & Save Test" button used to check only options A and B, so a
        // question with blank C and D could be saved and shown to students as two
        // empty radio buttons.
        assertNotNull(InputValidator.validateQuestion("What is 2+2?", "3", "4", "", "6"));
        assertNotNull(InputValidator.validateQuestion("What is 2+2?", "3", "4", "5", "   "));
        assertNotNull(InputValidator.validateQuestion("", "3", "4", "5", "6"));
        assertNotNull(InputValidator.validateQuestion(null, null, null, null, null));

        String tooLong = "o".repeat(InputValidator.OPTION_MAX_LENGTH + 1);
        assertNotNull(InputValidator.validateQuestion("What is 2+2?", tooLong, "4", "5", "6"));
        assertNull(InputValidator.validateQuestion("What is 2+2?",
                "o".repeat(InputValidator.OPTION_MAX_LENGTH), "4", "5", "6"));

        // questions.question_text is a TEXT column, but a paste or an imported file that
        // exceeds this is a mistake worth reporting instead of letting MySQL answer with
        // "Data too long for column 'question_text'".
        assertNotNull(InputValidator.validateQuestion(
                "q".repeat(InputValidator.QUESTION_TEXT_MAX_LENGTH + 1), "3", "4", "5", "6"));
        assertNull(InputValidator.validateQuestion(
                "q".repeat(InputValidator.QUESTION_TEXT_MAX_LENGTH), "3", "4", "5", "6"));
    }

    @Test
    @DisplayName("topic is optional but length-capped")
    void enforcesTopicRules() {
        assertNull(InputValidator.validateTopic(null));
        assertNull(InputValidator.validateTopic("Loops"));
        assertNotNull(InputValidator.validateTopic("t".repeat(InputValidator.TOPIC_MAX_LENGTH + 1)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parsePositiveInt returns -1 for anything that is not a positive int")
    void parsesPositiveIntegers() {
        assertEquals(15, InputValidator.parsePositiveInt("15"));
        assertEquals(15, InputValidator.parsePositiveInt("  15  "));
        assertEquals(Integer.MAX_VALUE, InputValidator.parsePositiveInt(String.valueOf(Integer.MAX_VALUE)));

        assertEquals(-1, InputValidator.parsePositiveInt("0"));
        assertEquals(-1, InputValidator.parsePositiveInt("-5"));
        assertEquals(-1, InputValidator.parsePositiveInt("abc"));
        assertEquals(-1, InputValidator.parsePositiveInt("1.5"));
        assertEquals(-1, InputValidator.parsePositiveInt(""));
        assertEquals(-1, InputValidator.parsePositiveInt("   "));
        assertEquals(-1, InputValidator.parsePositiveInt(null));
        assertEquals(-1, InputValidator.parsePositiveInt("99999999999999999999"),
                "values that overflow int must not be accepted");
    }

    @Test
    @DisplayName("the duration cap is low enough that minutes * 60 cannot overflow")
    void durationCapPreventsOverflow() {
        // The UI stores total_time_seconds as an int computed from minutes * 60.
        assertTrue(InputValidator.MAX_TEST_MINUTES * 60 > 0,
                "the maximum allowed duration must not overflow when converted to seconds");
        assertEquals(86_400, InputValidator.MAX_TEST_MINUTES * 60);

        // Any duration that would overflow int is far above the cap, so the UI's
        // validation rejects it before the multiplication ever happens.
        int overflowingMinutes = (Integer.MAX_VALUE / 60) + 1;
        assertTrue(overflowingMinutes * 60 < 0, "sanity: this many minutes does overflow");
        assertTrue(overflowingMinutes > InputValidator.MAX_TEST_MINUTES,
                "the cap must reject every duration that would overflow");
    }

    @Test
    @DisplayName("formatDuration keeps the seconds instead of truncating them")
    void formatsDurationWithoutTruncation() {
        // The UI used to print totalSeconds / 60, so a 90-second test showed as
        // "1 mins" and a 30-second test as "0 mins".
        assertEquals("0 mins", InputValidator.formatDuration(0));
        assertEquals("0 mins", InputValidator.formatDuration(-30));
        assertEquals("0m 30s", InputValidator.formatDuration(30));
        assertEquals("1 min", InputValidator.formatDuration(60));
        assertEquals("1m 30s", InputValidator.formatDuration(90));
        assertEquals("2 mins", InputValidator.formatDuration(120));
        assertEquals("15 mins", InputValidator.formatDuration(900));
    }
}
