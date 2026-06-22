package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mark a {@code SUBMITTED} complaint as a duplicate of another (the parent, identified by
 * ticket number). Reason is optional but useful audit context.
 */
public record MarkDuplicateRequest(
        @NotBlank @Size(max = 20) String parentTicketNo,
        @Size(max = 500) String reason
) {
}

