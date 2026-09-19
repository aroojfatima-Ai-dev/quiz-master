package com.quiz.dao;

import com.quiz.db.DBConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for handling quiz test results and student attempts in MySQL.
 */
public class ResultDAO {

    /**
     * Inserts a student's test score result into the 'results' table.
     */
    public boolean saveResult(int testId, int studentId, int score, int total, int overtimeSeconds) throws SQLException {
        String sql = "INSERT INTO results (test_id, student_id, score, total, overtime_seconds) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, testId);
            ps.setInt(2, studentId);
            ps.setInt(3, score);
            ps.setInt(4, total);
            ps.setInt(5, overtimeSeconds);

            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Retrieves test results for a specific student ID (strict authorization filter).
     */
    public List<Map<String, Object>> getResultsByStudent(int studentId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT r.id, r.test_id, t.title AS test_title, t.language, r.score, r.total, "
                   + "r.overtime_seconds, r.taken_at "
                   + "FROM results r "
                   + "JOIN tests t ON r.test_id = t.id "
                   + "WHERE r.student_id = ? "
                   + "ORDER BY r.taken_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, studentId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("testId", rs.getInt("test_id"));
                    row.put("testTitle", rs.getString("test_title"));
                    row.put("language", rs.getString("language"));
                    row.put("score", rs.getInt("score"));
                    row.put("total", rs.getInt("total"));
                    row.put("overtimeSeconds", rs.getInt("overtime_seconds"));
                    row.put("takenAt", rs.getTimestamp("taken_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    /**
     * Retrieves test attempt submissions by students for tests created by a specific teacher.
     */
    public List<Map<String, Object>> getSubmissionsForTeacher(int teacherId, Integer filterTestId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT r.id, r.test_id, t.title AS test_title, u.username AS student_name, u.email AS student_email, ")
                .append("r.score, r.total, r.overtime_seconds, r.taken_at ")
                .append("FROM results r ")
                .append("JOIN tests t ON r.test_id = t.id ")
                .append("JOIN users u ON r.student_id = u.id ")
                .append("WHERE t.created_by = ? ");

        if (filterTestId != null && filterTestId > 0) {
            sql.append("AND t.id = ? ");
        }
        sql.append("ORDER BY r.taken_at DESC");

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            ps.setInt(1, teacherId);
            if (filterTestId != null && filterTestId > 0) {
                ps.setInt(2, filterTestId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("testId", rs.getInt("test_id"));
                    row.put("testTitle", rs.getString("test_title"));
                    row.put("studentName", rs.getString("student_name"));
                    row.put("studentEmail", rs.getString("student_email"));
                    row.put("score", rs.getInt("score"));
                    row.put("total", rs.getInt("total"));
                    row.put("overtimeSeconds", rs.getInt("overtime_seconds"));
                    row.put("takenAt", rs.getTimestamp("taken_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }
}
