package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;

/**
 * Lightweight row for the staff / technician complaint lists (Stage 16). Carries the columns
 * the FE table needs to render a row and drill into the detail view; intentionally omits
 * description, reason fields, history and images so the list payload stays bounded.
 *
 * <p>For names of {@code assignedEngineerId} / {@code assignedTechnicianId}, the FE should
 * batch-resolve via {@code GET /api/v1/staff/users?ids=...} (Stage 14.5).</p>
 */
public record ComplaintListItemResponse(
        Long id,
        String ticketNo,
        Long categoryId,
        ComplaintSeverity severity,
        ComplaintStatus status,
        boolean slaBreached,
        Long distributionCenterId,
        Long assignedEngineerId,
        Long assignedTechnicianId,
        String contactMobile,
        OffsetDateTime submittedAt,
        OffsetDateTime slaDeadline,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt
) {
}

