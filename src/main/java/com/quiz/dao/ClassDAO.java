package com.quiz.dao;

import com.quiz.db.DBConnection;

import java.sql.*;
import java.util.*;

/**
 * Data Access Object for handling Classes and Student Enrollments in MySQL.
 */
public class ClassDAO {

    /**
     * Thrown when a class code is already taken.
     */
    public static class DuplicateClassCodeException extends Exception {
        public DuplicateClassCodeException(String message) {
            super(message);
        }
    }

    /**
     * Creates a new class with the teacher-chosen 6-character class code.
     */
    public boolean createClass(int teacherId, String className, String classCode)
            throws DuplicateClassCodeException, SQLException {
        String trimmedCode = classCode.trim().toUpperCase();
        String sql = "INSERT INTO classes (teacher_id, class_name, class_code) VALUES (?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, teacherId);
            ps.setString(2, className.trim());
            ps.setString(3, trimmedCode);

            int rows = ps.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                throw new DuplicateClassCodeException("Class code '" + trimmedCode + "' is already taken. Please choose a different code.");
            }
            throw e;
        }
    }

    /**
     * Enrolls a student in a class using the teacher's username and the 6-character class code.
     * Validates teacher existence, class ownership, and existing enrollment.
     */
    public boolean joinClass(int studentId, String teacherUsername, String classCode)
            throws SQLException, IllegalArgumentException, IllegalStateException {

        String trimmedTeacher = teacherUsername.trim();
        String trimmedCode = classCode.trim().toUpperCase();

        try (Connection conn = DBConnection.getConnection()) {
            // 1. Find teacher ID by username
            int teacherId = -1;
            String findTeacherSql = "SELECT id FROM users WHERE LOWER(username) = LOWER(?) AND role = 'teacher'";
            try (PreparedStatement ps = conn.prepareStatement(findTeacherSql)) {
                ps.setString(1, trimmedTeacher);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        teacherId = rs.getInt("id");
                    }
                }
            }

            if (teacherId == -1) {
                throw new IllegalArgumentException("Teacher username '" + trimmedTeacher + "' was not found.");
            }

            // 2. Find class ID matching teacher and class_code
            int classId = -1;
            String findClassSql = "SELECT id FROM classes WHERE teacher_id = ? AND UPPER(class_code) = ?";
            try (PreparedStatement ps = conn.prepareStatement(findClassSql)) {
                ps.setInt(1, teacherId);
                ps.setString(2, trimmedCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        classId = rs.getInt("id");
                    }
                }
            }

            if (classId == -1) {
                throw new IllegalArgumentException("Class code '" + trimmedCode + "' is invalid or does not belong to teacher '" + trimmedTeacher + "'.");
            }

            // 3. Check if student is already enrolled
            String checkEnrollSql = "SELECT id FROM enrollments WHERE class_id = ? AND student_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkEnrollSql)) {
                ps.setInt(1, classId);
                ps.setInt(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("You are already enrolled in this class.");
                    }
                }
            }

            // 4. Insert enrollment
            String insertSql = "INSERT INTO enrollments (class_id, student_id) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, classId);
                ps.setInt(2, studentId);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /**
     * Retrieves all classes created by a specific teacher along with enrolled student count.
     */
    public List<Map<String, Object>> getClassesByTeacher(int teacherId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT c.id, c.class_name, c.class_code, c.created_at, "
                   + "COUNT(e.id) AS student_count "
                   + "FROM classes c "
                   + "LEFT JOIN enrollments e ON c.id = e.class_id "
                   + "WHERE c.teacher_id = ? "
                   + "GROUP BY c.id, c.class_name, c.class_code, c.created_at "
                   + "ORDER BY c.id DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, teacherId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("className", rs.getString("class_name"));
                    row.put("classCode", rs.getString("class_code"));
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    row.put("studentCount", rs.getInt("student_count"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    /**
     * Retrieves all classes a student is enrolled in.
     */
    public List<Map<String, Object>> getEnrolledClassesForStudent(int studentId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT c.id AS class_id, c.class_name, c.class_code, u.username AS teacher_name, e.joined_at "
                   + "FROM enrollments e "
                   + "JOIN classes c ON e.class_id = c.id "
                   + "JOIN users u ON c.teacher_id = u.id "
                   + "WHERE e.student_id = ? "
                   + "ORDER BY e.joined_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, studentId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("classId", rs.getInt("class_id"));
                    row.put("className", rs.getString("class_name"));
                    row.put("classCode", rs.getString("class_code"));
                    row.put("teacherName", rs.getString("teacher_name"));
                    row.put("joinedAt", rs.getTimestamp("joined_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    /**
     * Deletes a class belonging to the given teacher:
     * 1. Unassigns any tests tied to this class (sets tests.class_id = NULL)
     * 2. Deletes all student enrollment records for this class
     * 3. Deletes the class itself
     */
    public boolean deleteClass(int classId, int teacherId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Verify ownership
                String verifySql = "SELECT id FROM classes WHERE id = ? AND teacher_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(verifySql)) {
                    ps.setInt(1, classId);
                    ps.setInt(2, teacherId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                    }
                }

                // 2. Unassign tests
                String unassignTestsSql = "UPDATE tests SET class_id = NULL WHERE class_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(unassignTestsSql)) {
                    ps.setInt(1, classId);
                    ps.executeUpdate();
                }

                // 3. Delete enrollments
                String deleteEnrollmentsSql = "DELETE FROM enrollments WHERE class_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteEnrollmentsSql)) {
                    ps.setInt(1, classId);
                    ps.executeUpdate();
                }

                // 4. Delete class
                String deleteClassSql = "DELETE FROM classes WHERE id = ? AND teacher_id = ?";
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(deleteClassSql)) {
                    ps.setInt(1, classId);
                    ps.setInt(2, teacherId);
                    affected = ps.executeUpdate();
                }

                conn.commit();
                return affected > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Updates a class's 6-character code.
     * Verifies teacher ownership, validates format, and enforces uniqueness across all classes.
     */
    public boolean updateClassCode(int classId, int teacherId, String newCode)
            throws DuplicateClassCodeException, SQLException, IllegalArgumentException {
        if (newCode == null) {
            throw new IllegalArgumentException("Class code cannot be null.");
        }
        String trimmedCode = newCode.trim().toUpperCase();
        if (trimmedCode.length() != 6 || !trimmedCode.matches("^[a-zA-Z0-9]{6}$")) {
            throw new IllegalArgumentException("Class code must be exactly 6 alphanumeric characters (letters and numbers only).");
        }

        try (Connection conn = DBConnection.getConnection()) {
            // 1. Check ownership and current code
            String checkSql = "SELECT class_code FROM classes WHERE id = ? AND teacher_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, classId);
                ps.setInt(2, teacherId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Class not found or does not belong to you.");
                    }
                    String currentCode = rs.getString("class_code");
                    if (trimmedCode.equalsIgnoreCase(currentCode)) {
                        return true; // No change needed
                    }
                }
            }

            // 2. Update to new code
            String updateSql = "UPDATE classes SET class_code = ? WHERE id = ? AND teacher_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, trimmedCode);
                ps.setInt(2, classId);
                ps.setInt(3, teacherId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                    throw new DuplicateClassCodeException("Class code '" + trimmedCode + "' is already taken. Please choose a different code.");
                }
                throw e;
            }
        }
    }

    /**
     * Retrieves all enrolled students (username, email, joined_at) for a specific class ID.
     */
    public List<Map<String, Object>> getEnrolledStudents(int classId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT u.id, u.username, u.email, e.joined_at "
                   + "FROM enrollments e "
                   + "JOIN users u ON e.student_id = u.id "
                   + "WHERE e.class_id = ? "
                   + "ORDER BY u.username ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, classId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("username", rs.getString("username"));
                    row.put("email", rs.getString("email"));
                    row.put("joinedAt", rs.getTimestamp("joined_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }
}
