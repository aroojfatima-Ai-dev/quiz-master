package com.quiz.model;

/**
 * Represents a single row from the "users" table.
 * Password is intentionally NOT stored here — the UI should never need it.
 */
public class User {

    private final int id;
    private final String username;
    private final String email;
    private final String role;   // "teacher" or "student"

    public User(int id, String username, String email, String role) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
    }

    // Getters only — object cannot be modified after creation (immutability)
    public int getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }

    // Convenience checks used to decide which dashboard to show
    public boolean isTeacher() { return "teacher".equalsIgnoreCase(role); }
    public boolean isStudent() { return "student".equalsIgnoreCase(role); }

    @Override
    public String toString() { return username + " (" + role + ")"; }
}