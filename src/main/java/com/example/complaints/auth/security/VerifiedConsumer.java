package com.example.complaints.auth.security;

import java.io.Serializable;

/**
 * Principal pinned by {@link ConsumerVerificationFilter} after a successful consumer-verify token
 * parse. Used as the {@code @AuthenticationPrincipal} on every {@code /api/v1/consumer/**} endpoint.
 *
 * <p>Carries the three fields the controllers actually need: the external Consumer ID
 * ({@code consumerId}), the FK into {@code consumer_master} ({@code consumerMasterId}), and the
 * OTP-verified mobile that became the {@code contact_mobile} for any submitted complaint
 * (TECHNICAL_DESIGN.md §5.1).</p>
 *
 * <p>Intentionally lightweight — no {@code UserDetails} contract; consumers are not staff and
 * never reach Spring Security's authorization rules ({@code /consumer/**} is {@code permitAll()}
 * at the chain level; the filter is the actual gate).</p>
 */
public record VerifiedConsumer(
        String consumerId,
        Long consumerMasterId,
        String mobile
) implements Serializable {
}

