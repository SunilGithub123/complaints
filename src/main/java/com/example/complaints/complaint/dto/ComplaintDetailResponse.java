package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code GET /api/v1/consumer/complaints/{ticketNo}}. Stage 10b scope is the
 * confirmation / re-fetch view — enough to render the post-submit screen and survive a refresh.
 * Lifecycle history, technician identity, resolution notes, and feedback are intentionally
 * <b>not</b> exposed here; those land in Phase 5's consumer tracking slice.
 */
public record ComplaintDetailResponse(
        Long id,
        String ticketNo,
        String consumerId,
        String contactMobile,
        Long categoryId,
        String description,
        String location,
        ComplaintStatus status,
        OffsetDateTime submittedAt,
        OffsetDateTime slaDeadline,
        List<ComplaintImageResponse> images
) {
}

