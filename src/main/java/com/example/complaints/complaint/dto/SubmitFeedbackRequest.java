package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/consumer/complaints/{ticketNo}/feedback} (Stage 19).
 *
 * <p>One feedback row per closed complaint, enforced by both a service-layer
 * {@code existsByComplaintId} check and a {@code UNIQUE(complaint_id)} index on
 * {@code feedback}.</p>
 *
 * <p>{@code rating} is a 1–5 star scale. {@code comment} is optional free-text.</p>
 */
public record SubmitFeedbackRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 1000) String comment
) {
}

