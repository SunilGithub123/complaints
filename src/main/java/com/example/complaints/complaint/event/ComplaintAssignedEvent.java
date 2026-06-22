package com.example.complaints.complaint.event;

import com.example.complaints.complaint.model.ComplaintSeverity;

/**
 * First-time technician assignment (SUBMITTED → ASSIGNED). Carries enough context for Stage 21
 * to push to the assigned technician + cc the engineer; reassignment is a separate event so
 * dispatch tables can treat them differently (e.g. notify the <em>previous</em> technician on
 * reassignment too).
 */
public record ComplaintAssignedEvent(
        Long complaintId,
        String ticketNo,
        Long assignedTechnicianId,
        Long assignedEngineerId,
        Long distributionCenterId,
        ComplaintSeverity severity,
        Long actorUserId
) implements ComplaintEvent {
}

