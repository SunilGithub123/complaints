package com.example.complaints.notification.dto;

import com.example.complaints.notification.model.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Stage 21.1 — device registration response per
 * {@code docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md §3.1}.
 *
 * <p>{@code pushToken} is deliberately omitted — the FE already has it (it just sent
 * it), and we never echo it back per §6.2 (avoid log / cache leakage on FE side).</p>
 */
@Schema(description = "Device-token row as returned by register / refresh. pushToken is never echoed.")
public record DeviceTokenResponse(
        Long id,
        String deviceId,
        DevicePlatform platform,
        String appVersion,
        boolean active,
        @Schema(description = "DB-managed created_at (IST)")
        OffsetDateTime registeredAt,
        @Schema(description = "DB-managed updated_at (IST) — bumps on every refresh")
        OffsetDateTime updatedAt
) {
}

