package com.quiz.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Single responsibility: provide a MySQL connection.
 * Every DAO will request a connection from here — the URL/user/password
 * stay in one place across the whole project.
 */
public class DBConnection {

    // XAMPP's default MySQL: localhost:3306, user "root", empty password
    private static final String URL =
            "jdbc:mysql://localhost:3306/quizmaster"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASSWORD = "";

    private static boolean schemaInitialized = false;

    /**
     * Creates a new connection. The caller is responsible for closing it
     * (DAOs use try-with-resources for this).
     * @throws SQLException if MySQL is not running or the database name is wrong
     */
    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
        if (!schemaInitialized) {
            initDatabaseSchema(conn);
            schemaInitialized = true;
        }
        return conn;
    }

    /**
     * Initializes database tables and columns if missing (classes, enrollments, tests columns).
     */
    private static synchronized void initDatabaseSchema(Connection conn) {
        try (java.sql.Statement st = conn.createStatement()) {
            // 1. Create classes table
            st.executeUpdate("CREATE TABLE IF NOT EXISTS classes (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "teacher_id INT NOT NULL, " +
                    "class_name VARCHAR(100) NOT NULL, " +
                    "class_code VARCHAR(20) NOT NULL UNIQUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE CASCADE)");

            // 2. Create enrollments table
            st.executeUpdate("CREATE TABLE IF NOT EXISTS enrollments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "class_id INT NOT NULL, " +
                    "student_id INT NOT NULL, " +
                    "joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE, " +
                    "UNIQUE KEY unique_class_student (class_id, student_id))");

            // 3. Migrate tests table (add class_id and is_public if not present)
            try {
                st.executeUpdate("ALTER TABLE tests ADD COLUMN class_id INT NULL");
            } catch (SQLException ignored) {}

            try {
                st.executeUpdate("ALTER TABLE tests ADD COLUMN is_public BOOLEAN DEFAULT TRUE");
            } catch (SQLException ignored) {}

            try {
                st.executeUpdate("ALTER TABLE tests ADD COLUMN question_order ENUM('SEQUENTIAL', 'SHUFFLED') DEFAULT 'SEQUENTIAL'");
            } catch (SQLException ignored) {}

            try {
                st.executeUpdate("ALTER TABLE tests ADD CONSTRAINT fk_tests_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE SET NULL");
            } catch (SQLException ignored) {}

        } catch (SQLException e) {
            System.err.println("DB Schema Auto-Init Warning: " + e.getMessage());
        }
    }

    // Quick test: right-click this class in IntelliJ -> Run, to check the connection
    public static void main(String[] args) {
        try (Connection con = getConnection()) {
            System.out.println("DB connection OK -> " + con.getCatalog());
        } catch (SQLException e) {
            System.out.println("DB connection FAIL -> " + e.getMessage());
        }
    }
}