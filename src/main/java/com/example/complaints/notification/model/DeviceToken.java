package com.example.complaints.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Stage 21.1 — device token row backing
 * {@link com.example.complaints.notification.service.DeviceTokenService}. Maps to the
 * {@code device_token} table created in {@code V1.5__create_device_token.sql}.
 *
 * <p>Exactly one of {@code consumerMasterId} / {@code userId} is non-null (enforced by
 * {@code ck_device_token__principal_xor} on the DB side). Partial-unique indexes
 * guarantee at most one active row per {@code (principal_kind, device_id)} pair —
 * re-registering the same {@code device_id} is a refresh in-place per contract §3.1.</p>
 */
@Entity
@Table(name = "device_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_master_id")
    private Long consumerMasterId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DevicePlatform platform;

    @Column(name = "push_token", nullable = false, columnDefinition = "text")
    private String pushToken;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** DB-managed (DEFAULT now()) per the Stage 2.1 convention. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** DB-managed (DEFAULT now() + V1.3 trigger) per the Stage 2.1 convention. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}

