package com.example.complaints.auth.security;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
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

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtFactory(JwtProperties props) {
        this.props = props;
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 bytes for HS256 (was " + keyBytes.length + ").");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
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

    /** @throws JwtException if invalid / expired / signature mismatch. */
    public Claims parse(String jwt) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }

    // ----- shared -----

    private IssuedToken build(String subject, Map<String, Object> claims, Duration ttl, String jti) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        var builder = Jwts.builder()
                .issuer(props.issuer())
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claims(claims)
                .signWith(signingKey, Jwts.SIG.HS256);
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

