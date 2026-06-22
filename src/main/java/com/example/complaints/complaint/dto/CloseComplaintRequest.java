package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.Size;

/**
 * Engineer / Admin close-on-behalf payload. {@code slaBreachReason} is required only when the
 * complaint's {@code resolved_at} (or, for direct closes, the current time) is past
 * {@code sla_deadline}; otherwise it may be blank.
 */
public record CloseComplaintRequest(
        @Size(max = 500) String slaBreachReason
) {
}

