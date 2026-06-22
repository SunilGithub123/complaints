package com.example.complaints.complaint.event;

/**
 * Consumer submitted feedback for a CLOSED complaint. Stage 21 notification target: nobody
 * directly — but Phase 7 analytics will subscribe to this for per-technician / per-DC rating
 * aggregates. Kept in the v1 event vocabulary so the publish call lands now and the
 * downstream listener can be added without revisiting the service.
 */
public record FeedbackSubmittedEvent(
        Long complaintId,
        String ticketNo,
        Long assignedTechnicianId,
        Long assignedEngineerId,
        Long distributionCenterId,
        int rating,
        boolean hasComment
) implements ComplaintEvent {
}

