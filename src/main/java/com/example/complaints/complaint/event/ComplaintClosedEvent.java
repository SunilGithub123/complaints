package com.example.complaints.complaint.event;

import java.time.Instant;

/**
 * Engineer / admin closed a RESOLVED complaint. Stage 21 notification target: the consumer
 * (close-out + "rate this resolution" CTA, which is the consumer-facing trigger for the
 * Stage 19 feedback flow).
 */
public record ComplaintClosedEvent(
        Long complaintId,
        String ticketNo,
        Long consumerMasterId,
        Long assignedTechnicianId,
        Long assignedEngineerId,
        boolean slaBreached,
        Instant closedAt,
        Long actorUserId
) implements ComplaintEvent {
}

