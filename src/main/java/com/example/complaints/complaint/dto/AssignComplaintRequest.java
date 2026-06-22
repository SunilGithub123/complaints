package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import jakarta.validation.constraints.NotNull;

/**
 * Engineer/Admin request: assign a {@code SUBMITTED} complaint to a technician and set its
 * severity in one call (TECHNICAL_DESIGN.md §5.4).
 */
public record AssignComplaintRequest(
        @NotNull Long technicianId,
        @NotNull ComplaintSeverity severity
) {
}

