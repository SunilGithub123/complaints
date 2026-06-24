package com.example.complaints.notification.controller;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.notification.dto.DeviceRegistrationRequest;
import com.example.complaints.notification.dto.DeviceTokenResponse;
import com.example.complaints.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 21.1 — staff-side device registration / revoke. Gated by {@code JwtAuthFilter}
 * + {@code PasswordResetRequiredFilter} via the existing {@code /api/v1/staff/**}
 * {@code .authenticated()} matcher. See
 * {@code docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md §3.3}.
 */
@RestController
@RequestMapping("/api/v1/staff/devices")
@RequiredArgsConstructor
@Tag(name = "Staff Devices",
        description = "Push-notification device registration for authenticated staff users")
public class StaffDeviceController {

    private final DeviceTokenService service;

    @PostMapping
    @Operation(summary = "Register (or refresh) the calling staff user's device for push",
            description = "Idempotent: re-posting the same deviceId refreshes the push token in "
                    + "place. Returns 201 on first registration, 200 on refresh.")
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @Valid @RequestBody DeviceRegistrationRequest req
    ) {
        DeviceTokenService.RegistrationResult result =
                service.registerForUser(caller.userId(), req);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.response()));
    }

    @DeleteMapping("/{deviceId}")
    @Operation(summary = "Revoke the calling staff user's device (soft delete, idempotent)",
            description = "Flips active=false. Missing or already-inactive device → 204 no-op. "
                    + "Called by the FE logout reducer before clearing the JWT.")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable String deviceId
    ) {
        service.revokeForUser(caller.userId(), deviceId);
        return ResponseEntity.noContent().build();
    }
}

