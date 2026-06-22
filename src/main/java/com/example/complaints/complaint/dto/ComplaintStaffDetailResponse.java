package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Engineer / Admin detail view of a complaint. Superset of {@link ComplaintDetailResponse}
 * — exposes fields a consumer never sees (technician/engineer IDs, severity, breach flag,
 * resolution / rejection / cancellation context).
 *
 * <p>Returned by {@code GET /api/v1/staff/complaints/{id}}. History rows are fetched
 * separately via {@code /history} so the detail view doesn't grow unbounded for
 * long-lived complaints.</p>
 */
public record ComplaintStaffDetailResponse(
        Long id,
        String ticketNo,
        Long consumerMasterId,
        String contactMobile,
        Long categoryId,
        ComplaintSeverity severity,
        String description,
        String location,
        Long distributionCenterId,
        Long assignedEngineerId,
        Long assignedTechnicianId,
        Long parentComplaintId,
        ComplaintStatus status,
        boolean slaBreached,
        String resolutionNotes,
        String slaBreachReason,
        String cancellationReason,
        String rejectionReason,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime slaDeadline,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        long version,
        List<ComplaintImageResponse> images
) {
}

