package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.ChangePasswordRequest;
import com.example.complaints.auth.dto.LoginRequest;
import com.example.complaints.auth.dto.LoginResponse;
import com.example.complaints.auth.dto.RefreshTokenRequest;
import com.example.complaints.auth.mapper.UserAccountMapper;
import com.example.complaints.auth.model.RefreshToken;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.RefreshTokenRepository;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.security.JwtProperties;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffAuthServiceTest {

    private UserAccountRepository users;
    private RefreshTokenRepository refreshTokens;
    private StaffAuthService service;
    private JwtFactory jwt;

    private UserAccount admin;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);   // low cost = fast test

    /** In-memory refresh-token store for round-trip tests (login → change-password → refresh). */
    private final Map<String, RefreshToken> storedRefreshTokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        JwtProperties props = new JwtProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5),
                "complaints-api",
                "test-secret-must-be-at-least-32-bytes-long-_-_-_");
        jwt = new JwtFactory(props);
        service = new StaffAuthService(users, refreshTokens, encoder, jwt, new UserAccountMapper());

        admin = UserAccount.builder()
                .id(1L)
                .employeeId("ADMIN001")
                .passwordHash(encoder.encode("ChangeMe!123"))
                .role(UserRole.ADMIN)
                .fullName("Bootstrap Admin")
                .subdivisionId(10L)
                .enabled(true)
                .passwordResetRequired(true)
                .notificationsPushEnabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        storedRefreshTokens.clear();
        // Persist + look-up wiring shared by every test that exercises issueTokenPair / refresh.
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken rt = inv.getArgument(0);
            storedRefreshTokens.put(rt.getTokenHash(), rt);
            return rt;
        });
        when(refreshTokens.findByTokenHash(any(String.class)))
                .thenAnswer(inv -> Optional.ofNullable(storedRefreshTokens.get(inv.<String>getArgument(0))));
    }

    @Test
    @DisplayName("login: happy path returns token pair and staff summary")
    void login_success() {
        when(users.findByEmployeeId("ADMIN001")).thenReturn(Optional.of(admin));

        LoginResponse res = service.login(new LoginRequest("ADMIN001", "ChangeMe!123"));

        assertThat(res.accessToken()).isNotBlank();
        assertThat(res.refreshToken()).isNotBlank();
        assertThat(res.passwordResetRequired()).isTrue();
        assertThat(res.staff().employeeId()).isEqualTo("ADMIN001");
        assertThat(res.staff().role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("login: wrong password throws BAD_CREDENTIALS (same code as unknown user)")
    void login_wrongPassword() {
        when(users.findByEmployeeId("ADMIN001")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.login(new LoginRequest("ADMIN001", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_CREDENTIALS);
    }

    @Test
    @DisplayName("change-password: returns a fresh token pair with passwordResetRequired=false in claims and envelope")
    void changePassword_rotatesTokenPair() {
        when(users.findByEmployeeId("ADMIN001")).thenReturn(Optional.of(admin));
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        LoginResponse first = service.login(new LoginRequest("ADMIN001", "ChangeMe!123"));
        String oldAccess = first.accessToken();
        String oldRefresh = first.refreshToken();

        LoginResponse after = service.changePassword(admin.getId(),
                new ChangePasswordRequest("ChangeMe!123", "BrandNewPassword!42"));

        assertThat(after.accessToken()).isNotBlank().isNotEqualTo(oldAccess);
        assertThat(after.refreshToken()).isNotBlank().isNotEqualTo(oldRefresh);
        assertThat(after.passwordResetRequired()).isFalse();
        assertThat(after.staff().passwordResetRequired()).isFalse();

        // The new access JWT must carry the post-change flag — proves no stale claim leaks through.
        Claims claims = jwt.parse(after.accessToken());
        assertThat(claims.get(JwtFactory.CLAIM_PASSWORD_RESET, Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("change-password: old refresh token issued at login is rejected by /refresh")
    void changePassword_invalidatesPreviousRefreshToken() {
        when(users.findByEmployeeId("ADMIN001")).thenReturn(Optional.of(admin));
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        LoginResponse first = service.login(new LoginRequest("ADMIN001", "ChangeMe!123"));
        String preChangeRefresh = first.refreshToken();

        // Mirror the real repository's revokeAllForUser behaviour against our in-memory map.
        org.mockito.Mockito.doAnswer(inv -> {
            storedRefreshTokens.values().forEach(rt -> rt.setRevoked(true));
            return null;
        }).when(refreshTokens).revokeAllForUser(admin.getId());

        service.changePassword(admin.getId(),
                new ChangePasswordRequest("ChangeMe!123", "BrandNewPassword!42"));

        assertThatThrownBy(() -> service.refresh(new RefreshTokenRequest(preChangeRefresh)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
    }
}

