package com.example.complaints.complaint.model;

/**
 * Lifecycle states for a {@link Complaint}. See {@code chk_status} in {@code V1.0__init_schema.sql}.
 *
 * <p>Happy path: {@code SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED}.
 * Alternative terminals: {@code CANCELLED}, {@code REJECTED}, {@code DUPLICATE}.
 * There is no {@code RE_OPENED} in v1 — the consumer raises a new complaint instead (BRD §3.4).</p>
 */
public enum ComplaintStatus {
    SUBMITTED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED,
    CANCELLED, REJECTED, DUPLICATE
}

