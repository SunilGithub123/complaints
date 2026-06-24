package com.example.complaints.notification.repository;

import com.example.complaints.notification.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /** Used by the consumer register path to refresh in-place rather than insert a duplicate. */
    Optional<DeviceToken> findFirstByConsumerMasterIdAndDeviceIdAndActiveTrue(Long consumerMasterId, String deviceId);

    /** Used by the staff register path; symmetric to the consumer finder above. */
    Optional<DeviceToken> findFirstByUserIdAndDeviceIdAndActiveTrue(Long userId, String deviceId);

    /** Used by the consumer revoke path to locate the row (active or not) before flipping. */
    Optional<DeviceToken> findFirstByConsumerMasterIdAndDeviceId(Long consumerMasterId, String deviceId);

    /** Used by the staff revoke path; symmetric to the consumer finder above. */
    Optional<DeviceToken> findFirstByUserIdAndDeviceId(Long userId, String deviceId);

    /** Stage 21.2 fan-out — all active device rows for a staff user (one per registered device). */
    List<DeviceToken> findByUserIdAndActiveTrue(Long userId);

    /** Stage 21.2 fan-out — all active device rows for a consumer. */
    List<DeviceToken> findByConsumerMasterIdAndActiveTrue(Long consumerMasterId);
}

