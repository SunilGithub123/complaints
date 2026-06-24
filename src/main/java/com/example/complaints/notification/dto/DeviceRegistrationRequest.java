package com.example.complaints.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Stage 21.1 — device-registration payload per
 * {@code docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md §3.1}.
 *
 * <p>{@code platform} is bound as {@link String} (not the enum) so that an unknown value
 * surfaces as {@code DEVICE_PLATFORM_UNSUPPORTED} (400) per §8 rather than the generic
 * Jackson enum-binding 400.</p>
 */
public record DeviceRegistrationRequest(
        @NotBlank
        @Size(max = 64)
        @Schema(description = "FE-generated UUID, stable per physical device")
        String deviceId,

        @NotBlank
        @Schema(description = "ANDROID | IOS | WEB", allowableValues = {"ANDROID", "IOS", "WEB"})
        String platform,

        @NotBlank
        @Size(max = 4096)
        @Schema(description = "Raw FCM / APNs / web push token; never logged or returned")
        String pushToken,

        @Size(max = 32)
        @Schema(description = "Optional app version, useful for staged rollouts")
        String appVersion
) {
}

