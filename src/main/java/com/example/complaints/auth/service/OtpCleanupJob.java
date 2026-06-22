package com.example.complaints.auth.service;

import com.example.complaints.auth.repository.OtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Hourly cleanup — deletes {@code otp} rows older than 24 hours, both consumed and abandoned.
 * v1 retention rule from TECHNICAL_DESIGN.md §6 "Consumer OTP / Verification Flow" step 4.
 *
 * <p>Runs in IST per the hard rule (schedulers always specify zone).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OtpCleanupJob {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final OtpRepository otpRepository;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void purgeExpired() {
        Instant threshold = Instant.now().minus(RETENTION);
        int deleted = otpRepository.deleteByCreatedAtBefore(threshold);
        if (deleted > 0) {
            log.info("OTP cleanup deleted {} rows older than {}", deleted, threshold);
        }
    }
}

