package com.example.complaints.complaint.event;

/**
 * Subsequent technician change after the complaint was already ASSIGNED / IN_PROGRESS. Distinct
 * from {@link ComplaintAssignedEvent} so dispatch can notify the <em>previous</em> technician
 * ("you've been replaced on …") as well as the new one. Cross-DC reassignment by an admin
 * surfaces here as {@code previousDistributionCenterId != distributionCenterId} — the listener
 * may want to inform the previous DC's engineer too.
 */
public record ComplaintReassignedEvent(
        Long complaintId,
        String ticketNo,
        Long previousTechnicianId,
        Long assignedTechnicianId,
        Long previousDistributionCenterId,
        Long distributionCenterId,
        Long assignedEngineerId,
        Long actorUserId,
        String reason
) implements ComplaintEvent {
}

