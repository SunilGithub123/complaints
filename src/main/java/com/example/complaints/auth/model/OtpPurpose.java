package com.example.complaints.auth.model;

/**
 * Purpose of an OTP record. {@code CONSUMER_VERIFY} drives the consumer per-action verification
 * flow (Phase 3); {@code STAFF_PASSWORD_RESET} is reserved for v2 (see TECHNICAL_DESIGN.md §6).
 */
public enum OtpPurpose {
    CONSUMER_VERIFY,
    STAFF_PASSWORD_RESET
}

