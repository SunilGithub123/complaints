package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code GET /api/v1/consumer/complaints/{ticketNo}} (Stage 17 enriched).
 * Carries the consumer-safe lifecycle view: severity, SLA-breach flag, resolution / closure
 * timestamps. Staff identities (engineer / technician IDs), internal reasons (rejection /
 * cancellation), and audit user IDs are <b>never</b> exposed here — those stay on
 * {@link ComplaintStaffDetailResponse}.
 *
 * <p>History lives on its own endpoint ({@code /history}) so the detail payload stays bounded;
 * the consumer-safe history shape ({@link ConsumerComplaintHistoryEntryResponse}) likewise
 * omits {@code changedByUserId}.</p>
 */
public record ComplaintDetailResponse(
        Long id,
        String ticketNo,
        String consumerId,
        String contactMobile,
        Long categoryId,
        ComplaintSeverity severity,
        String description,
        String location,
        ComplaintStatus status,
        boolean slaBreached,
        OffsetDateTime submittedAt,
        OffsetDateTime slaDeadline,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "True iff the consumer has already submitted feedback for this complaint. "
                        + "Lets the FE hide the Rate button on first paint without a probe POST.")
        boolean feedbackSubmitted,
        List<ComplaintImageResponse> images
) {
}
