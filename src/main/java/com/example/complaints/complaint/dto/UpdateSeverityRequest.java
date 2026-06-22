package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import jakarta.validation.constraints.NotNull;

/** Update severity later in the lifecycle (TECHNICAL_DESIGN.md §5.4). Status unchanged. */
public record UpdateSeverityRequest(
        @NotNull ComplaintSeverity severity
) {
}

