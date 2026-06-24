package com.example.complaints.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 21.2.2 — bound from {@code app.device-token-sweep.*}. Controls the nightly
 * job that flips long-idle {@code device_token} rows to {@code active = false}.
 *
 * <p>A row's idleness is measured against {@code updated_at} (DB trigger refreshes
 * this on every register / refresh per V1.3). The default of 60 days mirrors the
 * window most FCM SDKs treat tokens as live before recommending re-registration.</p>
 *
 * @param inactivityDays days since {@code updated_at} after which an active row is swept
 * @param enabled        kill-switch — set to {@code false} to skip the schedule entirely
 */
@ConfigurationProperties(prefix = "app.device-token-sweep")
public record DeviceTokenSweepProperties(
        int inactivityDays,
        boolean enabled
) {
    public DeviceTokenSweepProperties {
        if (inactivityDays <= 0) inactivityDays = 60;
    }
}

