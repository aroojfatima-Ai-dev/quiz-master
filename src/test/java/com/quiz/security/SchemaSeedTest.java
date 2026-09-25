package com.quiz.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the commented-out demo accounts in {@code schema.sql}.
 *
 * <p>The file used to advertise two hashes of "teacher123" and "student123" that were
 * placeholders: anyone who uncommented the block and imported the schema got accounts that
 * could never log in, with nothing to say why. These tests read the real file and check
 * that every advertised hash verifies against the password named in the same comment.
 */
class SchemaSeedTest {

    private static final Pattern HASH = Pattern.compile("'(PBKDF2:[^']+)'");

    @Test
    @DisplayName("the demo hashes in schema.sql really are teacher123 / student123")
    void demoHashesVerify() throws Exception {
        Path schema = Path.of("schema.sql");
        assumeTrue(Files.exists(schema), "schema.sql is part of the repository");

        String hash = null;
        boolean sawTeacher = false;
        boolean sawStudent = false;

        for (String line : Files.readAllLines(schema, StandardCharsets.UTF_8)) {
            Matcher matcher = HASH.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            hash = matcher.group(1);
            if (line.contains("'teacher'")) {
                sawTeacher = true;
                assertTrue(PasswordUtil.verify("teacher123", hash),
                        "the seed teacher hash must verify against teacher123");
            } else if (line.contains("'student'")) {
                sawStudent = true;
                assertTrue(PasswordUtil.verify("student123", hash),
                        "the seed student hash must verify against student123");
            }
            assertFalse(PasswordUtil.needsRehash(hash),
                    "the seed hash should use the current cost factor");
        }

        assertNotNull(hash, "schema.sql should still document the demo accounts");
        assertTrue(sawTeacher && sawStudent, "both demo accounts should be seeded");
    }

    @Test
    @DisplayName("schema.sql declares every table the DAOs query, with the columns they read")
    void schemaCoversEveryTable() throws Exception {
        Path schema = Path.of("schema.sql");
        assumeTrue(Files.exists(schema), "schema.sql is part of the repository");

        String sql = Files.readString(schema, StandardCharsets.UTF_8).toLowerCase();
        for (String table : List.of("users", "classes", "enrollments", "tests", "questions", "results")) {
            assertTrue(sql.contains("create table if not exists " + table),
                    "schema.sql must create " + table);
        }
        for (String column : List.of("class_id", "is_public", "expiry_action", "total_time_seconds",
                "overtime_seconds", "correct_option", "topic", "difficulty")) {
            assertTrue(sql.contains(column), "schema.sql is missing the column " + column);
        }

        // The runtime bootstrap now declares the same indexes as this file, so an
        // application-created database and a manually imported one are identical.
        for (String index : List.of("idx_tests_created_by", "idx_tests_class_id", "idx_questions_test_id",
                "idx_results_student_id", "idx_results_test_id")) {
            assertTrue(sql.contains(index), "schema.sql is missing the index " + index);
        }
    }

    @Test
    @DisplayName("the runtime bootstrap never adds a second foreign key on tests.class_id")
    void runtimeBootstrapDoesNotDuplicateTheClassForeignKey() throws Exception {
        Path bootstrap = Path.of("src/main/java/com/quiz/db/DBConnection.java");
        assumeTrue(Files.exists(bootstrap), "the source tree is present");

        String source = Files.readString(bootstrap, StandardCharsets.UTF_8);

        // The CREATE TABLE for a fresh database already declares the constraint, and
        // MySQL/MariaDB accept a second one on the same column, so the ALTER has to be
        // guarded. Without the guard an application-created database carried a duplicate
        // constraint that schema.sql does not declare, and every later start failed with a
        // duplicate-key error and printed "DB migration skipped".
        assertTrue(source.contains("hasForeignKeyOn(conn, \"tests\", \"class_id\")"),
                "DBConnection must check for an existing foreign key before adding one");
        assertTrue(source.contains("ADD CONSTRAINT fk_tests_class"),
                "the migration for pre-class databases must still be there");
    }

    @Test
    @DisplayName("schema.sql declares exactly one foreign key on tests.class_id")
    void schemaFileDeclaresOneClassForeignKey() throws Exception {
        Path schema = Path.of("schema.sql");
        assumeTrue(Files.exists(schema), "schema.sql is part of the repository");

        String sql = Files.readString(schema, StandardCharsets.UTF_8);

        // Only the statements that make up the 'tests' table count; enrollments has its
        // own (different) foreign key on a column of the same name.
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS tests");
        int end = sql.indexOf(";", start);
        String testsTable = sql.substring(start, end < 0 ? sql.length() : end);

        long declarations = testsTable.lines()
                .filter(line -> line.contains("FOREIGN KEY (class_id)"))
                .count();

        assertEquals(1, (int) declarations,
                "tests.class_id must have exactly one foreign key declaration, found "
                        + declarations);
    }

    @Test
    @DisplayName("the runtime bootstrap declares the same indexes as schema.sql")
    void runtimeBootstrapMatchesSchemaFile() throws Exception {
        Path bootstrap = Path.of("src/main/java/com/quiz/db/DBConnection.java");
        assumeTrue(Files.exists(bootstrap), "the source tree is present");

        String source = Files.readString(bootstrap, StandardCharsets.UTF_8);
        for (String index : List.of("idx_tests_created_by", "idx_tests_class_id", "idx_questions_test_id",
                "idx_results_student_id", "idx_results_test_id")) {
            assertTrue(source.contains(index),
                    "DBConnection must create " + index + " too, or the two schemas drift apart");
        }
    }
}
