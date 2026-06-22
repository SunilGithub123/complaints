package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;

/**
 * One row of a complaint's status-change audit trail. Returned by
 * {@code GET /api/v1/staff/complaints/{id}/history} in chronological order.
 *
 * <p>{@code fromStatus} is {@code null} for the initial {@code SUBMITTED} row.
 * {@code changedByUserId} is {@code null} for system-driven transitions (e.g. future SLA
 * scheduler flips).</p>
 */
public record ComplaintHistoryEntryResponse(
        Long id,
        ComplaintStatus fromStatus,
        ComplaintStatus toStatus,
        Long changedByUserId,
        String note,
        OffsetDateTime changedAt
) {
}

