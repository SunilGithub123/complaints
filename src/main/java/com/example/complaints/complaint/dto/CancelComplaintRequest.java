package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/consumer/complaints/{ticketNo}/cancel} (Stage 18).
 *
 * <p>The {@code reason} is optional — consumers may cancel without explanation (typically when
 * the issue resolved on its own, or they raised it by mistake). When present it's persisted on
 * the complaint's {@code cancellation_reason} column and surfaces on the staff detail view.</p>
 */
public record CancelComplaintRequest(
        @Size(max = 500) String reason
) {
}

