package com.example.complaints.complaint.event;

/**
 * Published from {@code ComplaintCreationService.submit} after the new {@code Complaint} row is
 * persisted. Stage 21 will use this to notify the engineer of the receiving DC ("new complaint
 * in your DC awaiting triage"). The consumer doesn't need a push for their own submit — the
 * synchronous response already carries the ticket + SLA.
 */
public record ComplaintSubmittedEvent(
        Long complaintId,
        String ticketNo,
        Long consumerMasterId,
        String contactMobile,
        Long categoryId,
        Long distributionCenterId
) implements ComplaintEvent {
}

