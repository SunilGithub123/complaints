package com.example.complaints.complaint.event;

/**
 * Consumer withdrew a SUBMITTED complaint. Stage 21 notification target: the engineer of the
 * DC ("complaint X was cancelled by consumer before you could triage"); skipped if the
 * complaint was never assigned (today's only valid pre-condition is {@code SUBMITTED} =
 * never assigned, but kept future-proof).
 *
 * <p>{@code consumerExternalId} is the consumer's external ID (Consumer master code), not
 * a JPA id — it's safe to surface to staff dashboards.</p>
 */
public record ComplaintCancelledEvent(
        Long complaintId,
        String ticketNo,
        Long consumerMasterId,
        String consumerExternalId,
        Long distributionCenterId,
        String reason
) implements ComplaintEvent {
}

