package com.quiz.dao;

import com.quiz.db.DBConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for managing QuizMaster tests and questions in MySQL.
 */
public class TestDAO {

    /**
     * Inserts a new test record into the 'tests' table with class_id and is_public flag.
     * Returns the auto-generated test ID.
     */
    public int createTest(String title, String language, int totalTimeSeconds, 
                           String expiryAction, int createdByUserId, 
                           Integer classId, boolean isPublic, String questionOrder) throws SQLException {
        String sql = "INSERT INTO tests (title, language, total_time_seconds, expiry_action, created_by, class_id, is_public, question_order) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

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
            ps.setString(8, "SHUFFLED".equalsIgnoreCase(questionOrder) ? "SHUFFLED" : "SEQUENTIAL");

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated test ID.");
    }

    public int createTest(String title, String language, int totalTimeSeconds, 
                           String expiryAction, int createdByUserId, 
                           Integer classId, boolean isPublic) throws SQLException {
        return createTest(title, language, totalTimeSeconds, expiryAction, createdByUserId, classId, isPublic, "SEQUENTIAL");
    }

    public int createTest(String title, String language, int totalTimeSeconds, 
                           String expiryAction, int createdByUserId) throws SQLException {
        return createTest(title, language, totalTimeSeconds, expiryAction, createdByUserId, null, true, "SEQUENTIAL");
    }

    /**
     * Inserts a new question associated with a test into the 'questions' table.
     */
    public boolean addQuestion(int testId, String questionText, String optionA, String optionB, 
                               String optionC, String optionD, String correctOption, 
                               String topic, String difficulty) throws SQLException {
        String sql = "INSERT INTO questions (test_id, question_text, option_a, option_b, option_c, option_d, correct_option, topic, difficulty) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, testId);
            ps.setString(2, questionText);
            ps.setString(3, optionA);
            ps.setString(4, optionB);
            ps.setString(5, optionC);
            ps.setString(6, optionD);
            ps.setString(7, correctOption);
            ps.setString(8, topic);
            ps.setString(9, difficulty);

            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Retrieves all tests created by a specific teacher user ID, including class name and public/private status.
     */
    public List<Map<String, Object>> getTestsByTeacher(int teacherId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT t.id, t.title, t.language, t.total_time_seconds, t.expiry_action, "
                   + "t.is_public, t.class_id, c.class_name, c.class_code, t.question_order, t.created_at "
                   + "FROM tests t "
                   + "LEFT JOIN classes c ON t.class_id = c.id "
                   + "WHERE t.created_by = ? ORDER BY t.id DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, teacherId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("title", rs.getString("title"));
                    row.put("language", rs.getString("language"));
                    row.put("totalTimeSeconds", rs.getInt("total_time_seconds"));
                    row.put("expiryAction", rs.getString("expiry_action"));
                    row.put("isPublic", rs.getBoolean("is_public"));
                    row.put("classId", rs.getObject("class_id") != null ? rs.getInt("class_id") : null);
                    row.put("className", rs.getString("class_name"));
                    row.put("classCode", rs.getString("class_code"));
                    row.put("questionOrder", rs.getString("question_order") != null ? rs.getString("question_order") : "SEQUENTIAL");
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
                   + "u.username AS teacher_name, t.is_public, c.class_name, t.question_order, t.created_at "
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
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("title", rs.getString("title"));
                    row.put("language", rs.getString("language"));
                    row.put("totalTimeSeconds", rs.getInt("total_time_seconds"));
                    row.put("expiryAction", rs.getString("expiry_action"));
                    row.put("teacherName", rs.getString("teacher_name") != null ? rs.getString("teacher_name") : "Unknown");
                    row.put("isPublic", rs.getBoolean("is_public"));
                    row.put("className", rs.getString("class_name"));
                    row.put("questionOrder", rs.getString("question_order") != null ? rs.getString("question_order") : "SEQUENTIAL");
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    public List<Map<String, Object>> getAllTests() throws SQLException {
        return getAllTestsForStudent(0);
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
                    Map<String, Object> row = new HashMap<>();
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
}
