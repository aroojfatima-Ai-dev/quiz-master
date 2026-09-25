-- =====================================================================
--  QuizMaster - complete database schema (MySQL 8.0+ / MariaDB 10.6+)
-- =====================================================================
--  The application also bootstraps this schema automatically on first
--  connection (see com.quiz.db.DBConnection). This file exists so the
--  database can be created by hand, reviewed, or imported with:
--
--      mysql -u root -p < schema.sql
--
--  Tables are declared in foreign-key dependency order:
--      users -> classes -> enrollments -> tests -> questions -> results
--
--  utf8mb4 is required: the app supports Urdu (RTL) question text, and
--  utf8mb3 cannot store the full character set.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS quizmaster
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE quizmaster;

-- ---------------------------------------------------------------------
-- users : teachers and students share one table, distinguished by role
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id       INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50)  NOT NULL UNIQUE,
    email    VARCHAR(120) NOT NULL UNIQUE,
    -- Holds a PBKDF2 hash (see com.quiz.security.PasswordUtil), not a
    -- plain-text password. 255 chars leaves room for the encoded form.
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(20)  NOT NULL DEFAULT 'student',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- classes : a teacher's class, joined by students with a 6-char code
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS classes (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    teacher_id INT          NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    class_code VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- enrollments : many-to-many link between students and classes
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS enrollments (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    class_id   INT NOT NULL,
    student_id INT NOT NULL,
    joined_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_class_student (class_id, student_id),
    FOREIGN KEY (class_id)   REFERENCES classes (id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users (id)   ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- tests : a quiz created by a teacher; public, or assigned to a class
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tests (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    title              VARCHAR(200) NOT NULL,
    language           VARCHAR(20)  NOT NULL DEFAULT 'English',
    total_time_seconds INT          NOT NULL DEFAULT 900,
    expiry_action      VARCHAR(20)  NOT NULL DEFAULT 'AUTO_SUBMIT',
    created_by         INT          NOT NULL,
    class_id           INT NULL,
    is_public          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tests_created_by (created_by),
    KEY idx_tests_class_id (class_id),
    FOREIGN KEY (created_by) REFERENCES users (id)   ON DELETE CASCADE,
    FOREIGN KEY (class_id)   REFERENCES classes (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- questions : four-option multiple choice questions belonging to a test
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS questions (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    test_id        INT          NOT NULL,
    question_text  TEXT         NOT NULL,
    option_a       VARCHAR(500) NOT NULL,
    option_b       VARCHAR(500) NOT NULL,
    option_c       VARCHAR(500) NOT NULL,
    option_d       VARCHAR(500) NOT NULL,
    correct_option CHAR(1)      NOT NULL,
    topic          VARCHAR(100) NOT NULL DEFAULT 'General',
    difficulty     VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    KEY idx_questions_test_id (test_id),
    FOREIGN KEY (test_id) REFERENCES tests (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- results : one row per test attempt by a student
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS results (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    test_id          INT NOT NULL,
    student_id       INT NOT NULL,
    score            INT NOT NULL DEFAULT 0,
    total            INT NOT NULL DEFAULT 0,
    overtime_seconds INT NOT NULL DEFAULT 0,
    taken_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_results_student_id (student_id),
    KEY idx_results_test_id (test_id),
    FOREIGN KEY (test_id)    REFERENCES tests (id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- =====================================================================
-- Optional demo data. Uncomment to seed one teacher, one student, one
-- class and one enrolment.
--
-- The two hashes below are real PBKDF2-HMAC-SHA256 hashes of "teacher123"
-- and "student123", produced by com.quiz.security.PasswordUtil with the
-- fixed salts shown, so the accounts can actually be logged into.
-- (They are also pinned by SchemaSeedTest, which fails the build if either
-- value stops matching its password.)
-- =====================================================================
-- INSERT INTO users (username, email, password, role) VALUES
--   ('prof_smith', 'smith@example.com',
--    'PBKDF2:210000:AAECAwQFBgcICQoLDA0ODw==:+WRaa9Yu9g8N/ZMdLu2CHwpmSSKH7ErY4Ti4IQkz84M=', 'teacher'),
--   ('ali_student', 'ali@example.com',
--    'PBKDF2:210000:EBESExQVFhcYGRobHB0eHw==:xmlfVVbH3zxLNMnd3Dt3fKgCt7tUab4jgMkPZlzVvuE=', 'student');
--
-- INSERT INTO classes (teacher_id, class_name, class_code)
--   SELECT id, 'Physics 101', 'PHY101' FROM users WHERE username = 'prof_smith';
--
-- INSERT INTO enrollments (class_id, student_id)
--   SELECT c.id, u.id FROM classes c, users u
--   WHERE c.class_code = 'PHY101' AND u.username = 'ali_student';
