package com.example.complaints.notification.model;

/**
 * Stage 21.2 — push payload {@code type} enum per contract §4 / §5. One value per
 * {@link com.example.complaints.complaint.event.ComplaintEvent} subtype.
 */
public enum PushType {
    COMPLAINT_SUBMITTED,
    COMPLAINT_ASSIGNED,
    COMPLAINT_REASSIGNED,
    COMPLAINT_RESOLVED,
    COMPLAINT_CLOSED,
    SLA_BREACHED,
    FEEDBACK_RECEIVED,
    COMPLAINT_CANCELLED,
    COMPLAINT_REJECTED
}

