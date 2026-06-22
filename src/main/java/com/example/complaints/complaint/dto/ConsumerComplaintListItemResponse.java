package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Row shape for {@code GET /api/v1/consumer/complaints} (Stage 17). Narrower than the staff
 * {@link ComplaintListItemResponse}: engineer / technician IDs, contact mobile, and DC are
 * deliberately omitted — the consumer doesn't need to see (and shouldn't be able to enumerate)
 * MSEB's internal staff allocation. The fields kept are the ones the tracking list actually
 * renders: ticket, category, severity, status badge, SLA state, the four lifecycle
 * timestamps, and the Stage 20.2 {@code feedbackSubmitted} flag.
 */
public record ConsumerComplaintListItemResponse(
        Long id,
        String ticketNo,
        Long categoryId,
        ComplaintSeverity severity,
        ComplaintStatus status,
        boolean slaBreached,
        OffsetDateTime submittedAt,
        OffsetDateTime slaDeadline,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "True iff feedback has been submitted for this complaint. Lets the "
                        + "tracking list render a Rated / Awaiting feedback hint without a per-row probe.")
        boolean feedbackSubmitted
) {
}

