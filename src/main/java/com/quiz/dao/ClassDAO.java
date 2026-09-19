package com.quiz.dao;

import com.quiz.db.DBConnection;
import com.quiz.validation.InputValidator;

import java.sql.*;
import java.util.*;

/**
 * Data Access Object for handling Classes and Student Enrollments in MySQL.
 */
public class ClassDAO {

    /** MySQL error code for a duplicate-key violation. */
    private static final int ER_DUP_ENTRY = 1062;

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
     *
     * <p>The code is normalised with {@link InputValidator#normalizeClassCode(String)},
     * which upper-cases using {@link java.util.Locale#ROOT}. The previous
     * {@code toUpperCase()} call followed the default locale, so on a Turkish system
     * an 'i' became a dotted capital 'İ' and the stored code no longer matched what
     * students typed.
     */
    public boolean createClass(int teacherId, String className, String classCode)
            throws DuplicateClassCodeException, SQLException {
        String trimmedCode = InputValidator.normalizeClassCode(classCode);
        String sql = "INSERT INTO classes (teacher_id, class_name, class_code) VALUES (?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, teacherId);
            ps.setString(2, className.trim());
            ps.setString(3, trimmedCode);

            int rows = ps.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new DuplicateClassCodeException("Class code '" + trimmedCode
                        + "' is already taken. Please choose a different code.");
            }
            throw e;
        }
    }

    /**
     * Enrolls a student in a class using the teacher's username and the 6-character
     * class code. Validates teacher existence, class ownership, and existing
     * enrollment.
     *
     * <p>The whole check-and-insert runs on one connection with auto-commit disabled,
     * and the {@code unique_class_student} constraint is treated as the authoritative
     * guard. Two students submitting at the same instant used to race between the
     * SELECT and the INSERT; the loser got a raw {@code SQLException} surfaced as
     * "Database Error" instead of a readable message.
     *
     * @throws IllegalArgumentException if the teacher or class code does not exist
     * @throws IllegalStateException    if the student is already enrolled
     */
    public boolean joinClass(int studentId, String teacherUsername, String classCode)
            throws SQLException, IllegalArgumentException, IllegalStateException {

        String trimmedTeacher = teacherUsername.trim();
        String trimmedCode = InputValidator.normalizeClassCode(classCode);

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
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
                    throw new IllegalArgumentException("Class code '" + trimmedCode
                            + "' is invalid or does not belong to teacher '" + trimmedTeacher + "'.");
                }

                // 3. Check if student is already enrolled (friendly, fast path)
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

                // 4. Insert enrollment. The unique_class_student constraint is the
                //    authoritative guard against two simultaneous requests.
                String insertSql = "INSERT INTO enrollments (class_id, student_id) VALUES (?, ?)";
                int rows;
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setInt(1, classId);
                    ps.setInt(2, studentId);
                    rows = ps.executeUpdate();
                } catch (SQLException e) {
                    if (isDuplicateKey(e)) {
                        throw new IllegalStateException("You are already enrolled in this class.");
                    }
                    throw e;
                }

                conn.commit();
                return rows > 0;

            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommitQuietly(conn);
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
                    Map<String, Object> row = new LinkedHashMap<>();
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
                    Map<String, Object> row = new LinkedHashMap<>();
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

    private static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == ER_DUP_ENTRY || "23000".equals(e.getSQLState());
    }

    /** Rolls back without masking the exception that is already being propagated. */
    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Nothing useful to do - the original exception is more important.
        }
    }

    /**
     * Restores auto-commit before the connection is closed or returned to a pool.
     * A failure here must not replace the exception already being propagated.
     */
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
