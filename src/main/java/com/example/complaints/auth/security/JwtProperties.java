package com.example.complaints.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Map;

/**
 * JWT configuration bound from {@code jwt.*} keys in {@code application.yml}.
 * See TECHNICAL_DESIGN.md 6 "Tokens".
 *
 * <h3>Key rotation (Stage 21.2.7)</h3>
 * Signing keys are addressed by a short opaque <b>kid</b> embedded in the JWT header.
 * Two equivalent shapes are accepted:
 * <ol>
 *   <li><b>Single secret (legacy / dev):</b> set only {@code jwt.secret}. It is auto-wrapped
 *       into a one-entry key map under {@code kid="v1"} and that becomes the active key.</li>
 *   <li><b>Multi-key (rotation):</b> set {@code jwt.keys.<kid>=...} for each live key and
 *       {@code jwt.active-kid} for the one used to sign new tokens. Old keys stay listed
 *       until every token minted under them has expired (worst case: {@code refreshTokenTtl}).</li>
 * </ol>
 *
 * <p>Rotation playbook for ops: add {@code jwt.keys.v2}, deploy, flip {@code active-kid} to {@code v2},
 * wait one refresh-token TTL, then drop {@code jwt.keys.v1}.</p>
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration consumerVerificationTtl,
        String issuer,
        String secret,
        String activeKid,
        Map<String, String> keys
) {

    private static final String DEFAULT_KID = "v1";

    @ConstructorBinding
    public JwtProperties {
        if (keys == null || keys.isEmpty()) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "jwt: either `secret` or `keys` must be configured.");
            }
            String kid = (activeKid == null || activeKid.isBlank()) ? DEFAULT_KID : activeKid;
            keys = Map.of(kid, secret);
            activeKid = kid;
        } else {
            if (activeKid == null || activeKid.isBlank()) {
                throw new IllegalStateException(
                        "jwt.active-kid must be set when jwt.keys is provided.");
            }
            if (!keys.containsKey(activeKid)) {
                throw new IllegalStateException(
                        "jwt.active-kid '" + activeKid + "' not found in jwt.keys "
                                + keys.keySet() + ".");
            }
            keys = Map.copyOf(keys);
        }
    }

    /**
     * Backward-compatible constructor used by tests / programmatic callers that predate
     * the {@code activeKid} + {@code keys} fields. The provided {@code secret} is wrapped
     * into a single-entry key map under the default kid ({@value #DEFAULT_KID}).
     */
    public JwtProperties(Duration accessTokenTtl,
                         Duration refreshTokenTtl,
                         Duration consumerVerificationTtl,
                         String issuer,
                         String secret) {
        this(accessTokenTtl, refreshTokenTtl, consumerVerificationTtl, issuer, secret, null, null);
    }
}
