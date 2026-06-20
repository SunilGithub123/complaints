package com.example.complaints.auth.model;

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
 * Staff account row. Backed by {@code user_account} (see {@code V1.0__init_schema.sql}).
 *
 * <p>The DB enforces:</p>
 * <ul>
 *   <li>{@code chk_staff_scope}: ADMIN → subdivision only; ENGINEER/TECHNICIAN → subdivision + DC.</li>
 *   <li>{@code uq_one_active_admin_per_subdivision} (partial unique).</li>
 *   <li>{@code uq_one_active_engineer_per_dc} (partial unique).</li>
 * </ul>
 * Service code must also enforce these at write time so users get a clean
 * {@link com.example.complaints.common.exception.BusinessException} instead of an opaque
 * {@code DataIntegrityViolationException}.
 */
@Entity
@Table(name = "user_account")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true, length = 50)
    private String employeeId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_reset_required", nullable = false)
    private boolean passwordResetRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "mobile", length = 15)
    private String mobile;

    /** Mandatory for every staff role. */
    @Column(name = "subdivision_id")
    private Long subdivisionId;

    /** Mandatory for ENGINEER/TECHNICIAN, NULL for ADMIN. */
    @Column(name = "distribution_center_id")
    private Long distributionCenterId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "notifications_push_enabled", nullable = false)
    private boolean notificationsPushEnabled;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Set by DB DEFAULT now() on INSERT; never modified after. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Set by DB DEFAULT now() on INSERT and bumped by the {@code set_updated_at()} trigger (V1.3). */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}

