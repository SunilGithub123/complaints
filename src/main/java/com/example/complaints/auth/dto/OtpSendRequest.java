package com.example.complaints.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/consumer/otp/send}. The {@code mobile} may differ
 * from the {@code consumer_master.mobile} on file — consumers can verify against any number
 * they currently have access to (TECHNICAL_DESIGN.md §5.1).
 */
public record OtpSendRequest(
        @NotBlank @Size(max = 50) String consumerId,
        @NotBlank
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "mobile must be 7-15 digits, optional leading +")
        String mobile
) {
}

