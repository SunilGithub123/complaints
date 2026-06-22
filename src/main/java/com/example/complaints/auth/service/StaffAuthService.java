package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.ChangePasswordRequest;
import com.example.complaints.auth.dto.LoginRequest;
import com.example.complaints.auth.dto.LoginResponse;
import com.example.complaints.auth.dto.RefreshTokenRequest;
import com.example.complaints.auth.dto.StaffSummaryResponse;
import com.example.complaints.auth.dto.UpdateMyProfileRequest;
import com.example.complaints.auth.mapper.UserAccountMapper;
import com.example.complaints.auth.model.RefreshToken;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.repository.RefreshTokenRepository;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.common.util.DateUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Staff login / refresh / change-password / logout.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>{@code login} returns a {@link LoginResponse} even when {@code passwordResetRequired=true}
 *       — the {@link com.example.complaints.auth.security.PasswordResetRequiredFilter} then forces
 *       the client through {@code /change-password} before any other endpoint accepts the token.</li>
 *   <li>{@code refresh} rotates the refresh token (revoke old, issue new) so a leaked refresh
 *       token has a single-use window.</li>
 *   <li>Refresh tokens are stored as SHA-256 hashes, not the raw JWT.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StaffAuthService {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthService.class);

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtFactory jwtFactory;
    private final UserAccountMapper mapper;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        UserAccount user = users.findByEmployeeId(req.employeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_CREDENTIALS));

        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.STAFF_ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Same error code as "user not found" — never reveal which side failed.
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        user.setLastLoginAt(Instant.now());
        return issueTokenPair(user);
    }

    @Transactional
    public LoginResponse refresh(RefreshTokenRequest req) {
        Claims claims;
        try {
            claims = jwtFactory.parse(req.refreshToken());
        } catch (JwtException ex) {
            log.debug("Refresh JWT rejected: {}", ex.getMessage());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (!JwtFactory.TYPE_REFRESH.equals(claims.get(JwtFactory.CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        String hash = sha256(req.refreshToken());
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        UserAccount user = users.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.STAFF_ACCOUNT_DISABLED);
        }

        // Rotate: revoke old, issue new pair.
        stored.setRevoked(true);
        stored.setLastUsedAt(Instant.now());
        return issueTokenPair(user);
    }

    @Transactional
    public LoginResponse changePassword(Long userId, ChangePasswordRequest req) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            // Re-use of the same password — treat as a validation failure.
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "New password must differ from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setPasswordResetRequired(false);
        // Revoke ALL outstanding refresh tokens — kicks every other live session AND invalidates
        // the caller's current pair so the access JWT (which still carries
        // passwordResetRequired = true in its claims) can't be reused. We then immediately mint
        // a fresh pair so the caller never has to chain refresh; this is the convergence point
        // documented in IMPLEMENTATION_LOG.md Stage 1 hotfix #1.
        refreshTokens.revokeAllForUser(user.getId());
        return issueTokenPair(user);
    }

    @Transactional
    public void logout(Long userId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshTokens.revokeAllForUser(userId);
            return;
        }
        refreshTokens.findByTokenHash(sha256(refreshToken))
                .ifPresent(rt -> rt.setRevoked(true));
    }

    @Transactional(readOnly = true)
    public StaffSummaryResponse me(Long userId) {
        return users.findById(userId)
                .map(mapper::toSummary)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * Self-service profile edit. Mutates only the caller's own mutable fields
     * (fullName, email, mobile, notificationsPushEnabled). Scope fields
     * (subdivisionId / distributionCenterId / role / employeeId) stay immutable
     * here — those are admin actions via {@code PUT /api/v1/admin/staff/{id}}.
     */
    @Transactional
    public StaffSummaryResponse updateMyProfile(Long userId, UpdateMyProfileRequest req) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.setFullName(req.fullName());
        user.setEmail(req.email());
        user.setMobile(req.mobile());
        user.setNotificationsPushEnabled(req.notificationsPushEnabled());
        return mapper.toSummary(user);
    }

    // ----- helpers -----

    private LoginResponse issueTokenPair(UserAccount user) {
        var access = jwtFactory.issueAccessToken(user);
        var refresh = jwtFactory.issueRefreshToken(user);
        RefreshToken stored = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(sha256(refresh.jwt()))
                .expiresAt(refresh.expiresAt())
                .revoked(false)
                .lastUsedAt(Instant.now())
                .build();
        refreshTokens.save(stored);

        return new LoginResponse(
                access.jwt(),
                DateUtils.toIst(access.expiresAt()),
                refresh.jwt(),
                DateUtils.toIst(refresh.expiresAt()),
                user.isPasswordResetRequired(),
                mapper.toSummary(user)
        );
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

