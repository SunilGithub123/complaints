package com.example.complaints.complaint.model;

/**
 * Severity classification. {@code NULL} at submission — the assigning engineer sets it
 * (Phase 4). See {@code chk_severity} in {@code V1.0__init_schema.sql}.
 */
public enum ComplaintSeverity {
    LOW, MEDIUM, HIGH
}

