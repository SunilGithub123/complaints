package com.example.complaints.notification.service;

import com.example.complaints.notification.DeviceTokenSweepProperties;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Stage 21.2.2 — nightly stale-token sweep. Flips long-idle {@code device_token}
 * rows to {@code active = false} so the Stage 21.2 fan-out stops targeting devices
 * that have not refreshed in a while (uninstalled app, OS-revoked permission, etc).
 *
 * <p>Cron: {@code 0 30 3 * * *} IST — 03:30 local, off-peak. Runs in IST per the
 * hard rule (schedulers always specify zone). Off-switch via
 * {@code app.device-token-sweep.enabled=false} — useful for tests and ad-hoc disables.</p>
 *
 * <p>Idleness is measured against {@code updated_at}, which the V1.3 trigger refreshes
 * on every register / refresh per contract §3.1. A 60-day default window matches the
 * typical FCM-token-live horizon.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenSweepJob {

    private final DeviceTokenRepository repo;
    private final DeviceTokenSweepProperties props;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void sweep() {
        if (!props.enabled()) {
            log.debug("Device-token sweep skipped (app.device-token-sweep.enabled=false)");
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.inactivityDays()));
        int swept = repo.markInactiveOlderThan(cutoff);
        if (swept > 0) {
            log.info("Device-token sweep marked {} row(s) inactive (idle since before {})",
                    swept, cutoff);
        }
    }
}

