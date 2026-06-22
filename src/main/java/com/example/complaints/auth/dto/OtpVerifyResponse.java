package com.example.complaints.auth.dto;

import java.time.OffsetDateTime;

/**
 * Response for {@code POST /api/v1/auth/consumer/otp/verify}. Carries the 5-minute, non-refreshable
 * consumer verification JWT and its expiry in IST so the FE can drive its countdown without
 * re-parsing the token (TECHNICAL_DESIGN.md §5.1 + §6).
 */
public record OtpVerifyResponse(
        String verificationToken,
        OffsetDateTime expiresAt
) {
}

