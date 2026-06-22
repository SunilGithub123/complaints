package com.example.complaints.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OtpVerifyRequest(
        @NotBlank @Size(max = 50) String consumerId,
        @NotBlank
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "mobile must be 7-15 digits, optional leading +")
        String mobile,
        @NotBlank @Pattern(regexp = "^[0-9]{4,8}$", message = "otp must be 4-8 digits") String otp
) {
}

