package com.example.complaints.auth.model;

/**
 * Staff roles. Consumers are NOT in {@code user_account}; they're verified per-action.
 * See TECHNICAL_DESIGN.md 6 and {@code V1.0__init_schema.sql chk_role}.
 */
public enum UserRole {
    ADMIN,
    ENGINEER,
    TECHNICIAN;

    /** Spring Security authority string, e.g. {@code "ROLE_ADMIN"}. */
    public String authority() {
        return "ROLE_" + name();
    }
}

