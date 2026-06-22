package com.example.complaints.complaint.dto;

import java.time.OffsetDateTime;

/**
 * Response shape for {@code POST /api/v1/consumer/complaints/{ticketNo}/feedback} (Stage 19).
 * Carries the persisted id + the rating / comment as stored + the (IST) submission timestamp,
 * so the FE can render the post-submit "thanks" screen without a follow-up GET.
 */
public record FeedbackResponse(
        Long id,
        int rating,
        String comment,
        OffsetDateTime submittedAt
) {
}

