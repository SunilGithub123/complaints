package com.example.complaints.complaint.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Backed by {@code complaint}. The aggregate root for the complaint lifecycle.
 *
 * <p>Stage 10b populates the submit-time subset: ticket, consumer FK, contact mobile, category,
 * description, location, DC (derived from consumer-master), {@code SUBMITTED} status, SLA
 * deadline. The assignment / resolution / closure fields stay {@code null} until Phase 4 services
 * fill them.</p>
 *
 * <p>Timestamps ({@code created_at}, {@code updated_at}) are DB-managed (V1.3 trigger) and surface
 * as read-only fields here — same pattern as Stage 1/2 entities.</p>
 */
@Entity
@Table(name = "complaint")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_no", nullable = false, unique = true, length = 20, updatable = false)
    private String ticketNo;

    @Column(name = "consumer_master_id", nullable = false)
    private Long consumerMasterId;

    @Column(name = "contact_mobile", nullable = false, length = 15)
    private String contactMobile;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private ComplaintSeverity severity;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "location", columnDefinition = "text")
    private String location;

    @Column(name = "distribution_center_id")
    private Long distributionCenterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ComplaintStatus status;

    @Column(name = "assigned_engineer_id")
    private Long assignedEngineerId;

    @Column(name = "assigned_technician_id")
    private Long assignedTechnicianId;

    @Column(name = "parent_complaint_id")
    private Long parentComplaintId;

    @Column(name = "sla_deadline", nullable = false)
    private Instant slaDeadline;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

    @Column(name = "sla_breach_reason", columnDefinition = "text")
    private String slaBreachReason;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Optimistic-lock counter (Phase 4). Incremented by Hibernate on every flush; mismatched
     * values on update fire {@code ObjectOptimisticLockingFailureException}, which
     * {@link com.example.complaints.common.exception.GlobalExceptionHandler} maps to
     * {@code COMPLAINT_VERSION_CONFLICT} (409). Distinct from the time-based {@code updated_at}
     * column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}

