package com.quiz.dao;

import com.quiz.model.User;
import com.quiz.db.DBConnection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * Registers a new user. Returns true if inserted successfully.
     */
    public boolean registerUser(String username, String email,
                                 String password, String role)
            throws DuplicateUserException, SQLException {

        String sql = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, password);
            ps.setString(4, role);

            int rows = ps.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("username")) {
                    throw new DuplicateUserException("Username already exists.");
                } else if (msg.contains("email")) {
                    throw new DuplicateUserException("Email already exists.");
                } else {
                    throw new DuplicateUserException("Username or email already exists.");
                }
            }
            throw e;
        }
    }

    /**
     * Looks up a user by username OR email and verifies the password (plain text).
     * Returns a User object on success, or null if no match.
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
                if (rs.next()) {
                    String storedPassword = rs.getString("password");

                    if (storedPassword != null && storedPassword.equals(password)) {
                        // Build the User using its constructor (no setters exist).
                        return new User(
                                rs.getInt("id"),
                                rs.getString("username"),
                                rs.getString("email"),
                                rs.getString("role")
                        );
                    }
                }
            }
        }
        return null; // No match or wrong password.
    }
}
