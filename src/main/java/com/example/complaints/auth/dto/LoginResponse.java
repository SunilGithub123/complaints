package com.example.complaints.auth.dto;

import java.time.OffsetDateTime;

public record LoginResponse(
        String accessToken,
        OffsetDateTime accessTokenExpiresAt,
        String refreshToken,
        OffsetDateTime refreshTokenExpiresAt,
        boolean passwordResetRequired,
        StaffSummaryResponse staff
) {}

