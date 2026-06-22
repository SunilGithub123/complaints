package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Technician resolution payload. {@code resolutionNotes} are mandatory (audit trail).
 *
 * <p>{@code slaBreachReason} is conditionally required: empty is fine when the deadline is
 * still in the future at resolution time; non-blank is mandatory if the SLA has already
 * elapsed (server checks against {@code complaint.sla_deadline} in IST). On a missing reason
 * for a breached complaint the service throws {@code SLA_BREACH_REASON_REQUIRED}.</p>
 */
public record ResolveComplaintRequest(
        @NotBlank @Size(max = 2000) String resolutionNotes,
        @Size(max = 500) String slaBreachReason
) {
}

