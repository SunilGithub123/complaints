package com.example.complaints.auth.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 21.2.7 — verifies the rotation contract documented on {@link JwtProperties}:
 * a token signed under the previous {@code kid} must still verify after ops flips
 * {@code active-kid} (as long as the old key is still listed). Tokens carrying a {@code kid}
 * that is not in {@code jwt.keys} must be rejected — typo-in-config must never silently pass.
 */
class JwtKeyRotationTest {

    private static final String K1 = "test-secret-v1-padding-padding-padding-padding";
    private static final String K2 = "test-secret-v2-padding-padding-padding-padding";

    private static JwtProperties props(String activeKid, Map<String, String> keys) {
        return new JwtProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5),
                "complaints-api", null, activeKid, keys);
    }

    @Test
    @DisplayName("token minted under previous kid still verifies after rotation flips active-kid")
    void rotatedKey_oldTokenStillVerifies() {
        Map<String, String> phase1 = new LinkedHashMap<>();
        phase1.put("v1", K1);
        JwtFactory before = new JwtFactory(props("v1", phase1));

        var issued = before.issueConsumerVerificationToken("MH00010001", 42L, "+919900000001");

        Map<String, String> phase2 = new LinkedHashMap<>();
        phase2.put("v1", K1); // retained until v1 tokens expire
        phase2.put("v2", K2);
        JwtFactory after = new JwtFactory(props("v2", phase2));

        var claims = after.parse(issued.jwt());
        assertThat(claims.get(JwtFactory.CLAIM_CONSUMER_MASTER_ID, Long.class)).isEqualTo(42L);
    }

    @Test
    @DisplayName("token carrying an unknown kid is rejected — config typo does not silently pass")
    void unknownKid_rejected() {
        JwtFactory issuer = new JwtFactory(props("v1", Map.of("v1", K1)));
        var issued = issuer.issueConsumerVerificationToken("MH00010001", 42L, "+919900000001");

        // Verifier no longer lists v1 — simulates an ops deploy that removed the old key too early.
        JwtFactory verifier = new JwtFactory(props("v2", Map.of("v2", K2)));
        assertThatThrownBy(() -> verifier.parse(issued.jwt()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("kid");
    }
}

