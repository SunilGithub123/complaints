package com.example.complaints.consumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Read-only consumer record sourced from the external EB system. Backed by {@code consumer_master}
 * (see {@code V1.0__init_schema.sql}). The {@code datasync} module owns writes (Phase 7);
 * complaint + OTP code paths only read.
 */
@Entity
@Table(name = "consumer_master")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConsumerMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_id", nullable = false, unique = true, length = 50)
    private String consumerId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "mobile", nullable = false, length = 15)
    private String mobile;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "address")
    private String address;

    @Column(name = "distribution_center_id")
    private Long distributionCenterId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Generated(event = EventType.INSERT)
    @Column(name = "last_synced_at", insertable = false, updatable = false)
    private Instant lastSyncedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}

