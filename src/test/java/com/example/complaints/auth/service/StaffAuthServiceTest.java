package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.LoginRequest;
import com.example.complaints.auth.dto.LoginResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
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

    private UserAccount admin;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);   // low cost = fast test

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        JwtProperties props = new JwtProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5),
                "complaints-api",
                "test-secret-must-be-at-least-32-bytes-long-_-_-_");
        JwtFactory jwt = new JwtFactory(props);
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
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
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
}

