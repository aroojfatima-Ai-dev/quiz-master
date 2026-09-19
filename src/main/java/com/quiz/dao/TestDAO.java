package com.quiz.dao;

import com.quiz.db.DBConnection;
import com.quiz.model.Question;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for managing QuizMaster tests and questions in MySQL.
 */
public class TestDAO {

    private static final String INSERT_TEST_SQL =
            "INSERT INTO tests (title, language, total_time_seconds, expiry_action, created_by, class_id, is_public) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_QUESTION_SQL =
            "INSERT INTO questions (test_id, question_text, option_a, option_b, option_c, option_d, correct_option, topic, difficulty) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Creates a test together with all of its questions in a single transaction.
     *
     * <p>The UI previously called {@link #createTest} once and then {@link #addQuestion}
     * once per question. Each of those opened its own auto-commit connection, so a
     * failure part-way through (a dropped connection, an over-long option, ...) left a
     * permanently orphaned test holding only some of its questions, and a 20-question
     * test cost 21 separate connections. If any part fails now, nothing is written.
     *
     * @param questions the questions to attach; must not be null or empty
     * @return the generated test id
     * @throws IllegalArgumentException if no questions are supplied
     * @throws SQLException             if the write fails, in which case the whole
     *                                  test is rolled back
     */
    public int createTestWithQuestions(String title, String language, int totalTimeSeconds,
                                       String expiryAction, int createdByUserId,
                                       Integer classId, boolean isPublic,
                                       List<Question> questions) throws SQLException {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("A test must contain at least one question.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int testId = insertTest(conn, title, language, totalTimeSeconds,
                        expiryAction, createdByUserId, classId, isPublic);

                for (Question q : questions) {
                    insertQuestion(conn, testId, q);
                }

                conn.commit();
                return testId;

            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommitQuietly(conn);
            }
        }
    }

    /**
     * Inserts a new test record into the 'tests' table with class_id and is_public flag.
     * Prefer {@link #createTestWithQuestions} when the questions are already known.
     *
     * @return the auto-generated test ID
     */
    public int createTest(String title, String language, int totalTimeSeconds,
                          String expiryAction, int createdByUserId,
                          Integer classId, boolean isPublic) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return insertTest(conn, title, language, totalTimeSeconds,
                    expiryAction, createdByUserId, classId, isPublic);
        }
    }

    public int createTest(String title, String language, int totalTimeSeconds,
                          String expiryAction, int createdByUserId) throws SQLException {
        return createTest(title, language, totalTimeSeconds, expiryAction, createdByUserId, null, true);
    }

    /**
     * Inserts a new question associated with a test into the 'questions' table.
     * Prefer {@link #createTestWithQuestions} when adding several questions at once.
     */
    public boolean addQuestion(int testId, String questionText, String optionA, String optionB,
                               String optionC, String optionD, String correctOption,
                               String topic, String difficulty) throws SQLException {
        Question question = new Question(questionText, optionA, optionB, optionC, optionD,
                correctOption, topic, difficulty);
        try (Connection conn = DBConnection.getConnection()) {
            return insertQuestion(conn, testId, question);
        }
    }

    /**
     * Retrieves all tests created by a specific teacher user ID, including class name
     * and public/private status.
     */
    public List<Map<String, Object>> getTestsByTeacher(int teacherId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT t.id, t.title, t.language, t.total_time_seconds, t.expiry_action, "
                   + "t.is_public, t.class_id, c.class_name, c.class_code, t.created_at "
                   + "FROM tests t "
                   + "LEFT JOIN classes c ON t.class_id = c.id "
                   + "WHERE t.created_by = ? ORDER BY t.id DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, teacherId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("title", rs.getString("title"));
                    row.put("language", rs.getString("language"));
                    row.put("totalTimeSeconds", rs.getInt("total_time_seconds"));
                    row.put("expiryAction", rs.getString("expiry_action"));
                    row.put("isPublic", rs.getBoolean("is_public"));
                    row.put("classId", rs.getObject("class_id") != null ? rs.getInt("class_id") : null);
                    row.put("className", rs.getString("class_name"));
                    row.put("classCode", rs.getString("class_code"));
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    /**
     * Retrieves all tests accessible to a specific student:
     * (a) Tests marked is_public = true, PLUS
     * (b) Tests assigned to a class the student is enrolled in.
     */
    public List<Map<String, Object>> getAllTestsForStudent(int studentId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT DISTINCT t.id, t.title, t.language, t.total_time_seconds, t.expiry_action, "
                   + "u.username AS teacher_name, t.is_public, c.class_name, t.created_at "
                   + "FROM tests t "
                   + "LEFT JOIN users u ON t.created_by = u.id "
                   + "LEFT JOIN classes c ON t.class_id = c.id "
                   + "WHERE t.is_public = TRUE "
                   + "   OR t.class_id IN (SELECT class_id FROM enrollments WHERE student_id = ?) "
                   + "ORDER BY t.id DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, studentId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readStudentTestRow(rs));
                }
            }
        }
        return list;
    }

    /**
     * Retrieves every test in the system, regardless of visibility.
     *
     * <p>This used to delegate to {@code getAllTestsForStudent(0)}, which silently
     * returned only the public tests because student id 0 is never enrolled anywhere.
     * It now queries the whole table as its name promises.
     */
    public List<Map<String, Object>> getAllTests() throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT DISTINCT t.id, t.title, t.language, t.total_time_seconds, t.expiry_action, "
                   + "u.username AS teacher_name, t.is_public, c.class_name, t.created_at "
                   + "FROM tests t "
                   + "LEFT JOIN users u ON t.created_by = u.id "
                   + "LEFT JOIN classes c ON t.class_id = c.id "
                   + "ORDER BY t.id DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(readStudentTestRow(rs));
            }
        }
        return list;
    }

    /**
     * Retrieves all questions associated with a specific test ID.
     */
    public List<Map<String, Object>> getQuestionsByTestId(int testId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, test_id, question_text, option_a, option_b, option_c, option_d, "
                   + "correct_option, topic, difficulty "
                   + "FROM questions WHERE test_id = ? ORDER BY id ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, testId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("testId", rs.getInt("test_id"));
                    row.put("questionText", rs.getString("question_text"));
                    row.put("optionA", rs.getString("option_a"));
                    row.put("optionB", rs.getString("option_b"));
                    row.put("optionC", rs.getString("option_c"));
                    row.put("optionD", rs.getString("option_d"));
                    row.put("correctOption", rs.getString("correct_option"));
                    row.put("topic", rs.getString("topic"));
                    row.put("difficulty", rs.getString("difficulty"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private int insertTest(Connection conn, String title, String language, int totalTimeSeconds,
                           String expiryAction, int createdByUserId,
                           Integer classId, boolean isPublic) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_TEST_SQL, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, title);
            ps.setString(2, language);
            ps.setInt(3, totalTimeSeconds);
            ps.setString(4, expiryAction);
            ps.setInt(5, createdByUserId);
            if (classId != null && classId > 0) {
                ps.setInt(6, classId);
            } else {
                ps.setNull(6, Types.INTEGER);
            }
            ps.setBoolean(7, isPublic);

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated test ID.");
    }

    private boolean insertQuestion(Connection conn, int testId, Question q) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_QUESTION_SQL)) {

            ps.setInt(1, testId);
            ps.setString(2, q.getQuestionText());
            ps.setString(3, q.getOptionA());
            ps.setString(4, q.getOptionB());
            ps.setString(5, q.getOptionC());
            ps.setString(6, q.getOptionD());
            ps.setString(7, q.getCorrectOption());
            ps.setString(8, q.getTopic());
            ps.setString(9, q.getDifficulty());

            return ps.executeUpdate() > 0;
        }
    }

    /** Maps one row of the student-facing test query into the shape the UI expects. */
    private Map<String, Object> readStudentTestRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getInt("id"));
        row.put("title", rs.getString("title"));
        row.put("language", rs.getString("language"));
        row.put("totalTimeSeconds", rs.getInt("total_time_seconds"));
        row.put("expiryAction", rs.getString("expiry_action"));
        String teacherName = rs.getString("teacher_name");
        row.put("teacherName", teacherName != null ? teacherName : "Unknown");
        row.put("isPublic", rs.getBoolean("is_public"));
        row.put("className", rs.getString("class_name"));
        row.put("createdAt", rs.getTimestamp("created_at"));
        return row;
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // The original exception matters more.
        }
    }

    private static void restoreAutoCommitQuietly(Connection conn) {
        try {
            if (!conn.isClosed()) {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // Best effort only.
        }
    }
}
