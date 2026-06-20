package com.example.complaints.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT configuration bound from {@code jwt.*} keys in {@code application.yml}.
 * See TECHNICAL_DESIGN.md 6 "Tokens".
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration consumerVerificationTtl,
        String issuer,
        String secret
) {
}

