package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Engineer/Admin rejection of a {@code SUBMITTED} complaint. Reason is mandatory (audit trail). */
public record RejectComplaintRequest(
        @NotBlank @Size(max = 500) String reason
) {
}

