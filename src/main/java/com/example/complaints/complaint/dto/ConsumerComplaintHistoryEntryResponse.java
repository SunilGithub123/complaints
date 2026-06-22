package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;

/**
 * Consumer-safe history row for {@code GET /api/v1/consumer/complaints/{ticketNo}/history}
 * (Stage 17). Identical to {@link ComplaintHistoryEntryResponse} <b>minus</b> {@code
 * changedByUserId} — consumers must not be able to enumerate MSEB staff IDs via the audit
 * trail. The {@code note} text is reused as-is; current usage is system-generated phrases
 * ("Assigned", "Closed (SLA breached)", "SLA breached", …) which carry no PII.
 */
public record ConsumerComplaintHistoryEntryResponse(
        Long id,
        ComplaintStatus fromStatus,
        ComplaintStatus toStatus,
        String note,
        OffsetDateTime changedAt
) {
}

