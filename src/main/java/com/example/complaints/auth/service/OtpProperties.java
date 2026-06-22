package com.example.complaints.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OTP issuance + verification configuration. Bound from {@code app.otp.*} keys in
 * {@code application.yml}. See TECHNICAL_DESIGN.md §6.
 *
 * @param length the number of decimal digits in the OTP (6 in v1)
 * @param ttl how long an issued OTP remains verifiable (5 min in v1)
 * @param maxPerMobilePerHour rate limit per recipient mobile (5 in v1)
 * @param cooldownSeconds enforced gap between consecutive sends to the same mobile (30 in v1)
 * @param maxAttempts verify attempts before the OTP is invalidated (5 in v1)
 */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        int length,
        Duration ttl,
        int maxPerMobilePerHour,
        int cooldownSeconds,
        int maxAttempts
) {
}

