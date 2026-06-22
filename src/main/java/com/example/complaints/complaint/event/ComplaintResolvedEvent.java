package com.example.complaints.complaint.event;

import java.time.Instant;

/**
 * Technician marked the complaint RESOLVED. Stage 21 notification target: the consumer
 * ("Your complaint is resolved — please confirm so we can close it"). {@code slaBreached}
 * is captured at resolve time and stays sticky on the entity; the listener can use it
 * verbatim instead of re-checking the deadline.
 */
public record ComplaintResolvedEvent(
        Long complaintId,
        String ticketNo,
        Long consumerMasterId,
        Long assignedTechnicianId,
        Long assignedEngineerId,
        boolean slaBreached,
        Instant resolvedAt,
        Long actorUserId
) implements ComplaintEvent {
}

