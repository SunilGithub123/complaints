package com.example.complaints.auth.security;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single place that mints / verifies HS256 JWTs. Three purposes, each with explicit per-purpose
 * builders so the call-sites read clearly. See TECHNICAL_DESIGN.md 6 "Tokens" and the Factory
 * pattern note in {@code .github/copilot-instructions.md}.
 *
 * <p>Token shapes:</p>
 * <ul>
 *   <li><b>Staff access</b>  — {@code typ=access},  claims: {@code sub=userId}, {@code emp}, {@code role}, {@code sub_id}, {@code dc_id}, {@code prr}.</li>
 *   <li><b>Staff refresh</b> — {@code typ=refresh}, claims: {@code sub=userId}, {@code jti}. Hash persisted in {@code refresh_token}.</li>
 *   <li><b>Consumer verification</b> — {@code typ=consumer}, claims: {@code sub=consumerId}, {@code mob}. 5 min TTL, NOT persisted, NOT refreshable.</li>
 * </ul>
 *
 * <p><b>Key rotation (Stage 21.2.7):</b> every minted token carries a {@code kid} header pointing
 * at the signing key in {@link JwtProperties#keys()}. Verification picks the key by {@code kid} so
 * ops can rotate the secret without invalidating tokens minted under the previous key.</p>
 */
@Component
public class JwtFactory {

    public static final String CLAIM_TYPE              = "typ";
    public static final String CLAIM_EMPLOYEE_ID       = "emp";
    public static final String CLAIM_ROLE              = "role";
    public static final String CLAIM_SUBDIVISION_ID    = "sub_id";
    public static final String CLAIM_DC_ID             = "dc_id";
    public static final String CLAIM_PASSWORD_RESET    = "prr";
    public static final String CLAIM_CONSUMER_MOBILE   = "mob";
    public static final String CLAIM_CONSUMER_MASTER_ID = "cmid";

    public static final String TYPE_ACCESS   = "access";
    public static final String TYPE_REFRESH  = "refresh";
    public static final String TYPE_CONSUMER = "consumer";

    private static final int HS256_MIN_KEY_BYTES = 32;

    private final JwtProperties props;
    private final Map<String, SecretKey> keysByKid;
    private final String activeKid;
    private final SecretKey activeKey;

    public JwtFactory(JwtProperties props) {
        this.props = props;
        this.activeKid = props.activeKid();
        Map<String, SecretKey> built = new LinkedHashMap<>();
        props.keys().forEach((kid, secret) -> built.put(kid, toHmacKey(kid, secret)));
        this.keysByKid = Map.copyOf(built);
        this.activeKey = this.keysByKid.get(activeKid);
    }

    private static SecretKey toHmacKey(String kid, String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.keys['" + kid + "'] must be at least " + HS256_MIN_KEY_BYTES
                            + " bytes for HS256 (was " + keyBytes.length + ").");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // ----- issue -----

    public IssuedToken issueAccessToken(UserAccount user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_ACCESS);
        claims.put(CLAIM_EMPLOYEE_ID, user.getEmployeeId());
        claims.put(CLAIM_ROLE, user.getRole().name());
        claims.put(CLAIM_SUBDIVISION_ID, user.getSubdivisionId());
        if (user.getDistributionCenterId() != null) {
            claims.put(CLAIM_DC_ID, user.getDistributionCenterId());
        }
        claims.put(CLAIM_PASSWORD_RESET, user.isPasswordResetRequired());
        return build(String.valueOf(user.getId()), claims, props.accessTokenTtl(), null);
    }

    public IssuedToken issueRefreshToken(UserAccount user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_REFRESH);
        return build(String.valueOf(user.getId()), claims, props.refreshTokenTtl(), UUID.randomUUID().toString());
    }

    public IssuedToken issueConsumerVerificationToken(String consumerId, Long consumerMasterId, String mobile) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_CONSUMER);
        claims.put(CLAIM_CONSUMER_MOBILE, mobile);
        claims.put(CLAIM_CONSUMER_MASTER_ID, consumerMasterId);
        return build(consumerId, claims, props.consumerVerificationTtl(), null);
    }

    // ----- verify -----

    /** @throws JwtException if invalid / expired / signature mismatch / unknown kid. */
    public Claims parse(String jwt) {
        return Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }

    /**
     * Resolves the verification key from the {@code kid} header. Tokens with no {@code kid}
     * (only possible from configurations that pre-date Stage 21.2.7) fall back to the active key
     * so a deploy that introduces the {@code kid} header does not invalidate already-issued tokens.
     * Tokens with an unknown {@code kid} are rejected — we do NOT silently accept the active key
     * for them, because that would mask a key-id typo in ops config.
     */
    private final LocatorAdapter<Key> keyLocator = new LocatorAdapter<>() {
        @Override
        protected Key locate(ProtectedHeader header) {
            String kid = header.getKeyId();
            if (kid == null || kid.isBlank()) {
                return activeKey;
            }
            SecretKey k = keysByKid.get(kid);
            if (k == null) {
                throw new JwtException("Unknown JWT kid: " + kid);
            }
            return k;
        }
    };

    // ----- shared -----

    private IssuedToken build(String subject, Map<String, Object> claims, Duration ttl, String jti) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        var builder = Jwts.builder()
                .header().keyId(activeKid).and()
                .issuer(props.issuer())
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claims(claims)
                .signWith(activeKey, Jwts.SIG.HS256);
        if (jti != null) {
            builder.id(jti);
        }
        String jwt = builder.compact();
        return new IssuedToken(jwt, exp);
    }

    public static UserRole roleFromClaims(Claims claims) {
        return UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));
    }

    /** Minted JWT plus its expiry — returned together so callers don't re-parse just to know TTL. */
    public record IssuedToken(String jwt, Instant expiresAt) {}
}

