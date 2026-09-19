package com.quiz.dao;

import com.quiz.model.User;
import com.quiz.db.DBConnection;
import com.quiz.security.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data Access Object for user-related database operations.
 */
public class UserDAO {

    /**
     * Thrown when a username or email is already taken.
     */
    public static class DuplicateUserException extends Exception {
        public DuplicateUserException(String message) {
            super(message);
        }
    }

    /**
     * Registers a new user. The password is hashed with {@link PasswordUtil} before
     * it reaches the database - it used to be written as plain text.
     *
     * @return true if the row was inserted
     */
    public boolean registerUser(String username, String email,
                                 String password, String role)
            throws DuplicateUserException, SQLException {

        String sql = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, ?)";
        String hashedPassword = PasswordUtil.hash(password);

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, hashedPassword);
            ps.setString(4, role);

            int rows = ps.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new DuplicateUserException(describeDuplicate(e));
            }
            throw e;
        }
    }

    /**
     * Looks up a user by username OR email and verifies the password.
     *
     * <p>Every row matching the identifier is checked, not just the first. A single
     * {@code WHERE username = ? OR email = ?} can legitimately match two different
     * accounts (one user's e-mail address can equal another user's username), and
     * examining only {@code rs.next()} once made the second account impossible to
     * log into.
     *
     * <p>If a matching account still holds a legacy plain-text password, it is
     * re-hashed transparently so the upgrade happens on first successful login.
     *
     * @return the authenticated {@link User}, or null when no account matches
     */
    public User findUserForLogin(String usernameOrEmail, String password)
            throws SQLException {

        String sql = "SELECT id, username, email, role, password FROM users "
                   + "WHERE username = ? OR email = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, usernameOrEmail);
            ps.setString(2, usernameOrEmail);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String storedPassword = rs.getString("password");

                    if (!PasswordUtil.verify(password, storedPassword)) {
                        continue;
                    }

                    int id = rs.getInt("id");
                    if (PasswordUtil.needsRehash(storedPassword)) {
                        upgradePasswordHash(conn, id, password);
                    }

                    // Build the User using its constructor (no setters exist).
                    return new User(
                            id,
                            rs.getString("username"),
                            rs.getString("email"),
                            rs.getString("role")
                    );
                }
            }
        }
        return null; // No match or wrong password.
    }

    /**
     * Re-hashes a legacy plain-text (or weak) password in place.
     * Failure here must not block the login that just succeeded, so it is logged
     * rather than propagated.
     */
    private void upgradePasswordHash(Connection conn, int userId, String plainPassword) {
        String updateSql = "UPDATE users SET password = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, PasswordUtil.hash(plainPassword));
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Could not upgrade stored password hash for user id "
                    + userId + ": " + e.getMessage());
        }
    }

    /** MySQL reports duplicate-key violations as 1062 / SQLState 23000. */
    private static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == 1062 || "23000".equals(e.getSQLState());
    }

    /**
     * Turns a duplicate-key error into a message that says which field clashed.
     * The constraint name is used in preference to the raw message text, which is
     * localised on some MySQL installations.
     */
    private static String describeDuplicate(SQLException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (detail.contains("email")) {
            return "Email already exists.";
        }
        if (detail.contains("username")) {
            return "Username already exists.";
        }
        return "Username or email already exists.";
    }
}
