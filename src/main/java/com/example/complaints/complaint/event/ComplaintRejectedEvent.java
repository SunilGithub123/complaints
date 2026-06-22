package com.example.complaints.complaint.event;

/**
 * Engineer / admin rejected a SUBMITTED complaint. Stage 21 notification target: the consumer
 * (with the rejection reason — required and human-facing). Distinct from
 * {@link ComplaintCancelledEvent} both in semantics ("MSEB refused" vs "consumer withdrew")
 * and in dispatch (different message copy).
 */
public record ComplaintRejectedEvent(
        Long complaintId,
        String ticketNo,
        Long consumerMasterId,
        Long distributionCenterId,
        String reason,
        Long actorUserId
) implements ComplaintEvent {
}

