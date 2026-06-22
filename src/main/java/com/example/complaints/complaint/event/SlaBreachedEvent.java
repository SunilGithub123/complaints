package com.example.complaints.complaint.event;

import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.Instant;

/**
 * Published per complaint by {@code SlaMonitorService} when it flips the {@code sla_breached}
 * flag. Stage 21 notification targets: the assigned engineer (always) + the assigned
 * technician (if any). The current {@link ComplaintStatus} is included so the dispatcher can
 * tailor copy ("SLA breached while still SUBMITTED" reads differently from "SLA breached
 * while IN_PROGRESS").
 *
 * <p>Note: this is published in a loop from a {@code @Scheduled} method. Each event has its
 * own AFTER_COMMIT delivery — the scheduler runs one big transaction, so all events land
 * together after that tx commits.</p>
 */
public record SlaBreachedEvent(
        Long complaintId,
        String ticketNo,
        Long assignedTechnicianId,
        Long assignedEngineerId,
        Long distributionCenterId,
        ComplaintStatus statusAtBreach,
        Instant slaDeadline,
        Instant breachedAt
) implements ComplaintEvent {
}

