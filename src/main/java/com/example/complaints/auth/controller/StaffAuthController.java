package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.ChangePasswordRequest;
import com.example.complaints.auth.dto.LoginRequest;
import com.example.complaints.auth.dto.LoginResponse;
import com.example.complaints.auth.dto.RefreshTokenRequest;
import com.example.complaints.auth.dto.StaffSummaryResponse;
import com.example.complaints.auth.dto.UpdateMyProfileRequest;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.service.StaffAuthService;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
@Tag(name = "Staff Auth", description = "Login, refresh, change-password, logout, me")
public class StaffAuthController {

    private final StaffAuthService authService;

    @PostMapping("/auth/login")
    @Operation(summary = "Login with employee ID + password")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(req)));
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Rotate refresh token; returns new access + refresh pair")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req)));
    }

    @PostMapping("/auth/change-password")
    @Operation(summary = "Change password and receive a fresh token pair",
            description = "Persists the new password, revokes every refresh token belonging to the "
                    + "caller (kicks all other sessions), and returns a brand-new access + refresh "
                    + "pair with passwordResetRequired = false in both the JWT claims and the "
                    + "response envelope. Callers do not need to chain /staff/auth/refresh afterwards.")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @Valid @RequestBody ChangePasswordRequest req) {
        requireAuth(me);
        return ResponseEntity.ok(ApiResponse.ok(authService.changePassword(me.userId(), req)));
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "Revoke the provided refresh token (or all sessions if body omitted)")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @RequestBody(required = false) RefreshTokenRequest req) {
        requireAuth(me);
        authService.logout(me.userId(), req == null ? null : req.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/me")
    @Operation(summary = "Current staff profile")
    public ResponseEntity<ApiResponse<StaffSummaryResponse>> me(@AuthenticationPrincipal AuthenticatedStaff me) {
        requireAuth(me);
        return ResponseEntity.ok(ApiResponse.ok(authService.me(me.userId())));
    }

    @PutMapping("/me")
    @Operation(summary = "Update my profile (self-service)",
            description = "Updates the caller's own mutable profile fields: fullName, email, "
                    + "mobile, notificationsPushEnabled. Scope fields (role, subdivisionId, "
                    + "distributionCenterId, employeeId) are immutable here — those are admin "
                    + "actions via PUT /api/v1/admin/staff/{id}.")
    public ResponseEntity<ApiResponse<StaffSummaryResponse>> updateMyProfile(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @Valid @RequestBody UpdateMyProfileRequest req) {
        requireAuth(me);
        return ResponseEntity.ok(ApiResponse.ok(authService.updateMyProfile(me.userId(), req)));
    }

    private static void requireAuth(AuthenticatedStaff me) {
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}

