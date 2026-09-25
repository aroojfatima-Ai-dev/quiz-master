package com.quiz.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Single responsibility: provide a MySQL connection and guarantee the schema exists.
 *
 * <p>Connection settings are read from system properties first, then environment
 * variables, then fall back to the XAMPP defaults (localhost:3306, user {@code root},
 * empty password). Credentials are no longer hard-coded, so the app can point at a
 * different server without recompiling:
 *
 * <pre>
 *   java -Dquizmaster.db.host=localhost -Dquizmaster.db.user=root -Dquizmaster.db.password=secret ...
 *   QUIZMASTER_DB_HOST=localhost QUIZMASTER_DB_USER=root QUIZMASTER_DB_PASSWORD=secret
 * </pre>
 *
 * <p>On the first successful connection the database and every table the DAOs use
 * are created if they are missing. Previously only {@code classes} and
 * {@code enrollments} were bootstrapped, so a fresh install failed immediately with
 * "Table 'quizmaster.users' doesn't exist" - and because {@code classes} declares a
 * foreign key to {@code users}, that table could not be created either.
 *
 * <p>All tables use {@code utf8mb4}: the app supports Urdu question text, which
 * cannot be stored in MySQL's 3-byte utf8mb3.
 */
public final class DBConnection {

    private static final String HOST = config("quizmaster.db.host", "QUIZMASTER_DB_HOST", "localhost");
    private static final String PORT = config("quizmaster.db.port", "QUIZMASTER_DB_PORT", "3306");
    private static final String DATABASE = config("quizmaster.db.name", "QUIZMASTER_DB_NAME", "quizmaster");
    private static final String USER = config("quizmaster.db.user", "QUIZMASTER_DB_USER", "root");
    private static final String PASSWORD = config("quizmaster.db.password", "QUIZMASTER_DB_PASSWORD", "");

    /**
     * Connection parameters shared by every URL.
     * {@code characterEncoding=UTF-8} makes Connector/J negotiate utf8mb4, which is
     * what keeps Urdu text intact on the wire as well as on disk.
     */
    private static final String PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$]+$");

    /**
     * MySQL error codes that must not be mistaken for "the database is missing".
     * 1044 = access denied for user to database, 1045 = access denied for user.
     */
    private static final int ACCESS_DENIED_FOR_DATABASE = 1044;
    private static final int ACCESS_DENIED = 1045;

    /** Set only after the schema bootstrap has fully succeeded. */
    private static final AtomicBoolean SCHEMA_READY = new AtomicBoolean(false);

    /** Guards the one-off bootstrap so concurrent callers cannot run it twice. */
    private static final Object INIT_LOCK = new Object();

    private DBConnection() {
        // Utility class - not instantiable.
    }

    /**
     * Creates a new connection. The caller is responsible for closing it
     * (DAOs use try-with-resources for this).
     *
     * <p>The first call also bootstraps the database and schema. If that fails, the
     * exception is propagated and the bootstrap is retried on the next call rather
     * than being silently marked as done.
     *
     * @throws SQLException if MySQL is unreachable, the credentials are wrong, or the
     *                      schema could not be created
     */
    public static Connection getConnection() throws SQLException {
        if (!SCHEMA_READY.get()) {
            bootstrap();
        }
        return DriverManager.getConnection(databaseUrl(), USER, PASSWORD);
    }

    /**
     * @return the JDBC URL for the QuizMaster database (useful in diagnostics)
     */
    public static String getDatabaseUrl() {
        return databaseUrl();
    }

    private static void bootstrap() throws SQLException {
        synchronized (INIT_LOCK) {
            if (SCHEMA_READY.get()) {
                return;
            }
            createDatabaseIfMissing();
            try (Connection conn = DriverManager.getConnection(databaseUrl(), USER, PASSWORD)) {
                createSchema(conn);
            }
            // Reached only when every statement above succeeded.
            SCHEMA_READY.set(true);
        }
    }

    private static void createDatabaseIfMissing() throws SQLException {
        // Fast path: the database is already there, so no CREATE privilege is needed.
        try (Connection probe = DriverManager.getConnection(databaseUrl(), USER, PASSWORD)) {
            return;
        } catch (SQLException notReady) {
            int code = notReady.getErrorCode();
            /*
             * Only a genuinely missing database justifies a CREATE. When the server
             * rejected the credentials, the CREATE would fail too and the teacher would be
             * told to "grant the CREATE privilege" - sending them to look at the wrong
             * problem when the password is simply wrong.
             */
            if (code == ACCESS_DENIED_FOR_DATABASE || code == ACCESS_DENIED) {
                throw new SQLException("MySQL refused the login for user '" + USER + "' on "
                        + HOST + ":" + PORT + " (error " + code + "). Check the credentials: system "
                        + "properties quizmaster.db.user / quizmaster.db.password, or environment "
                        + "variables QUIZMASTER_DB_USER / QUIZMASTER_DB_PASSWORD. Server said: "
                        + notReady.getMessage(), notReady);
            }
            // Anything else: fall through and try to create the database.
        }

        if (!IDENTIFIER.matcher(DATABASE).matches()) {
            throw new SQLException("Invalid database name '" + DATABASE
                    + "'. Only letters, digits, '_' and '$' are allowed.");
        }

        String ddl = "CREATE DATABASE IF NOT EXISTS `" + DATABASE + "`"
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Connection server = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement st = server.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new SQLException("Could not create database '" + DATABASE + "' on "
                    + HOST + ":" + PORT + ". Create it manually (mysql -u root -p < schema.sql) "
                    + "or grant the CREATE privilege to '" + USER + "'. Cause: " + e.getMessage(), e);
        }
    }

    /**
     * Creates every table the DAOs depend on, in foreign-key order.
     * All statements are idempotent so this is safe to run on an existing database.
     */
    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {

            // 1. users - referenced by classes, enrollments, tests and results.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(50) NOT NULL UNIQUE, "
                    + "email VARCHAR(120) NOT NULL UNIQUE, "
                    + "password VARCHAR(255) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL DEFAULT 'student', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 2. classes
            st.executeUpdate("CREATE TABLE IF NOT EXISTS classes ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "teacher_id INT NOT NULL, "
                    + "class_name VARCHAR(100) NOT NULL, "
                    + "class_code VARCHAR(20) NOT NULL UNIQUE, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 3. enrollments
            st.executeUpdate("CREATE TABLE IF NOT EXISTS enrollments ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "class_id INT NOT NULL, "
                    + "student_id INT NOT NULL, "
                    + "joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY unique_class_student (class_id, student_id), "
                    + "FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 4. tests
            st.executeUpdate("CREATE TABLE IF NOT EXISTS tests ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "language VARCHAR(20) NOT NULL DEFAULT 'English', "
                    + "total_time_seconds INT NOT NULL DEFAULT 900, "
                    + "expiry_action VARCHAR(20) NOT NULL DEFAULT 'AUTO_SUBMIT', "
                    + "created_by INT NOT NULL, "
                    + "class_id INT NULL, "
                    + "is_public BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "KEY idx_tests_created_by (created_by), "
                    + "KEY idx_tests_class_id (class_id), "
                    + "FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE SET NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 5. questions
            st.executeUpdate("CREATE TABLE IF NOT EXISTS questions ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "test_id INT NOT NULL, "
                    + "question_text TEXT NOT NULL, "
                    + "option_a VARCHAR(500) NOT NULL, "
                    + "option_b VARCHAR(500) NOT NULL, "
                    + "option_c VARCHAR(500) NOT NULL, "
                    + "option_d VARCHAR(500) NOT NULL, "
                    + "correct_option CHAR(1) NOT NULL, "
                    + "topic VARCHAR(100) NOT NULL DEFAULT 'General', "
                    + "difficulty VARCHAR(10) NOT NULL DEFAULT 'MEDIUM', "
                    + "KEY idx_questions_test_id (test_id), "
                    + "FOREIGN KEY (test_id) REFERENCES tests(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 6. results
            st.executeUpdate("CREATE TABLE IF NOT EXISTS results ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "test_id INT NOT NULL, "
                    + "student_id INT NOT NULL, "
                    + "score INT NOT NULL DEFAULT 0, "
                    + "total INT NOT NULL DEFAULT 0, "
                    + "overtime_seconds INT NOT NULL DEFAULT 0, "
                    + "taken_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "KEY idx_results_student_id (student_id), "
                    + "KEY idx_results_test_id (test_id), "
                    + "FOREIGN KEY (test_id) REFERENCES tests(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 7. Migration for databases created by an earlier version of the app,
            //    where 'tests' predates the class assignment feature. A duplicate
            //    column/constraint error simply means the migration already ran.
            migrateIgnoringDuplicate(st, "ALTER TABLE tests ADD COLUMN class_id INT NULL");
            migrateIgnoringDuplicate(st, "ALTER TABLE tests ADD COLUMN is_public BOOLEAN DEFAULT TRUE");
            migrateIgnoringDuplicate(st, "ALTER TABLE tests ADD CONSTRAINT fk_tests_class "
                    + "FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE SET NULL");

            // 8. The index declarations above only apply to a database created from
            //    scratch. These bring an existing database in line with schema.sql, which
            //    declares the same five indexes; a duplicate index name (1061) is ignored.
            migrateIgnoringDuplicate(st, "ALTER TABLE tests ADD INDEX idx_tests_created_by (created_by)");
            migrateIgnoringDuplicate(st, "ALTER TABLE tests ADD INDEX idx_tests_class_id (class_id)");
            migrateIgnoringDuplicate(st, "ALTER TABLE questions ADD INDEX idx_questions_test_id (test_id)");
            migrateIgnoringDuplicate(st, "ALTER TABLE results ADD INDEX idx_results_student_id (student_id)");
            migrateIgnoringDuplicate(st, "ALTER TABLE results ADD INDEX idx_results_test_id (test_id)");
        }
    }

    /**
     * Runs a best-effort migration statement, ignoring the errors MySQL raises when
     * it has already been applied: 1060 (duplicate column name), 1061 (duplicate key
     * name) and 1826 (duplicate foreign key constraint name).
     */
    private static void migrateIgnoringDuplicate(Statement st, String sql) {
        try {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            int code = e.getErrorCode();
            if (code != 1060 && code != 1061 && code != 1826) {
                System.err.println("DB migration skipped (" + sql + "): " + e.getMessage());
            }
        }
    }

    /**
     * Resolves a setting from a system property, then an environment variable,
     * then the supplied default. Blank values are treated as absent.
     */
    private static String config(String propertyKey, String envKey, String defaultValue) {
        String value = System.getProperty(propertyKey);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(envKey);
        }
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String databaseUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE + "?" + PARAMS;
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?" + PARAMS;
    }

    /**
     * Manual connectivity check: run this class to verify the database settings
     * and bootstrap the schema without starting the JavaFX UI.
     */
    public static void main(String[] args) {
        try (Connection con = getConnection()) {
            System.out.println("DB connection OK -> " + con.getCatalog());
            System.out.println("URL: " + getDatabaseUrl());
        } catch (SQLException e) {
            System.out.println("DB connection FAIL -> " + e.getMessage());
            System.exit(1);
        }
    }
}
