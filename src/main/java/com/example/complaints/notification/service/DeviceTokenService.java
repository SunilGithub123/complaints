package com.example.complaints.notification.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.notification.dto.DeviceRegistrationRequest;
import com.example.complaints.notification.dto.DeviceTokenResponse;
import com.example.complaints.notification.mapper.DeviceTokenMapper;
import com.example.complaints.notification.model.DevicePlatform;
import com.example.complaints.notification.model.DeviceToken;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Stage 21.1 — device-token CRUD service backing the four
 * {@code /api/v1/{consumer,staff}/devices/**} endpoints.
 *
 * <p>Per contract {@code STAGE_21_DEVICE_TOKEN_CONTRACT.md §3.1}: re-registering the
 * same {@code (principal, device_id)} is a <b>refresh</b> in-place — the partial-unique
 * indexes guarantee at most one active row per pair, so a refresh updates the
 * {@code push_token} / {@code platform} / {@code app_version} on the existing row rather
 * than creating a duplicate. Revoke flips {@code active=false} (soft delete) so the row
 * stays for audit and future re-registers reuse the same {@code id}.</p>
 *
 * <p>The {@code 403 DEVICE_NOT_OWNED_BY_*} cases documented in §3.2 / §3.3 are
 * unreachable under this implementation because every query is principal-scoped: a
 * foreign principal's row is simply invisible to the caller, so a revoke against
 * someone else's {@code device_id} is a 204 no-op (privacy-stronger than the contract
 * requires). The error codes stay reserved in {@link ErrorCode} for any future
 * cross-principal admin endpoint.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenService {

    private final DeviceTokenRepository repo;
    private final DeviceTokenMapper mapper;

    /** Carries the 200-vs-201 signal to the controller without leaking HTTP concerns into the service layer. */
    public record RegistrationResult(DeviceTokenResponse response, boolean created) {
    }

    @Transactional
    public RegistrationResult registerForConsumer(Long consumerMasterId, DeviceRegistrationRequest req) {
        DevicePlatform platform = parsePlatform(req.platform());
        Optional<DeviceToken> existing =
                repo.findFirstByConsumerMasterIdAndDeviceIdAndActiveTrue(consumerMasterId, req.deviceId());
        if (existing.isPresent()) {
            return new RegistrationResult(mapper.toResponse(refresh(existing.get(), platform, req)), false);
        }
        DeviceToken row = DeviceToken.builder()
                .consumerMasterId(consumerMasterId)
                .deviceId(req.deviceId())
                .platform(platform)
                .pushToken(req.pushToken())
                .appVersion(req.appVersion())
                .active(true)
                .build();
        DeviceToken saved = repo.save(row);
        log.info("Registered device {} for consumer {}", req.deviceId(), consumerMasterId);
        return new RegistrationResult(mapper.toResponse(saved), true);
    }

    @Transactional
    public RegistrationResult registerForUser(Long userId, DeviceRegistrationRequest req) {
        DevicePlatform platform = parsePlatform(req.platform());
        Optional<DeviceToken> existing =
                repo.findFirstByUserIdAndDeviceIdAndActiveTrue(userId, req.deviceId());
        if (existing.isPresent()) {
            return new RegistrationResult(mapper.toResponse(refresh(existing.get(), platform, req)), false);
        }
        DeviceToken row = DeviceToken.builder()
                .userId(userId)
                .deviceId(req.deviceId())
                .platform(platform)
                .pushToken(req.pushToken())
                .appVersion(req.appVersion())
                .active(true)
                .build();
        DeviceToken saved = repo.save(row);
        log.info("Registered device {} for user {}", req.deviceId(), userId);
        return new RegistrationResult(mapper.toResponse(saved), true);
    }

    @Transactional
    public void revokeForConsumer(Long consumerMasterId, String deviceId) {
        repo.findFirstByConsumerMasterIdAndDeviceId(consumerMasterId, deviceId)
                .filter(DeviceToken::isActive)
                .ifPresent(row -> {
                    row.setActive(false);
                    log.info("Revoked device {} (id={}) for consumer {}", deviceId, row.getId(), consumerMasterId);
                });
    }

    @Transactional
    public void revokeForUser(Long userId, String deviceId) {
        repo.findFirstByUserIdAndDeviceId(userId, deviceId)
                .filter(DeviceToken::isActive)
                .ifPresent(row -> {
                    row.setActive(false);
                    log.info("Revoked device {} (id={}) for user {}", deviceId, row.getId(), userId);
                });
    }

    private DevicePlatform parsePlatform(String raw) {
        try {
            return DevicePlatform.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.DEVICE_PLATFORM_UNSUPPORTED);
        }
    }

    private DeviceToken refresh(DeviceToken row, DevicePlatform platform, DeviceRegistrationRequest req) {
        row.setPlatform(platform);
        row.setPushToken(req.pushToken());
        row.setAppVersion(req.appVersion());
        // Hibernate dirty-check flushes on commit; updated_at bumps via the V1.3 trigger.
        log.info("Refreshed device {} (id={})", row.getDeviceId(), row.getId());
        return row;
    }
}

