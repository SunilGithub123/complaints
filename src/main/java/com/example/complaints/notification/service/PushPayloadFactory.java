package com.example.complaints.notification.service;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.complaint.event.ComplaintAssignedEvent;
import com.example.complaints.complaint.event.ComplaintCancelledEvent;
import com.example.complaints.complaint.event.ComplaintClosedEvent;
import com.example.complaints.complaint.event.ComplaintReassignedEvent;
import com.example.complaints.complaint.event.ComplaintRejectedEvent;
import com.example.complaints.complaint.event.ComplaintResolvedEvent;
import com.example.complaints.complaint.event.ComplaintSubmittedEvent;
import com.example.complaints.complaint.event.FeedbackSubmittedEvent;
import com.example.complaints.complaint.event.SlaBreachedEvent;
import com.example.complaints.notification.dto.PushPayload;
import com.example.complaints.notification.model.PushType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Stage 21.2 — renders {@link PushPayload} from each {@code ComplaintEvent} subtype.
 * Templates are English-only in v1 per FE sign-off §9.1; localisation moves to the
 * Stage 22 inbox row (bump to {@code schemaVersion=2} then).
 *
 * <p>{@code eventOccurredAt} is captured at the factory call (i.e. at AFTER_COMMIT),
 * not at event-publish, so the wire time matches what the FE sees as the "happened-at"
 * moment per §4.</p>
 *
 * <p>Title / body are kept terse and free of PII (no consumer name, no DC name).
 * {@code rejection-reason} is the one field that gets inlined into the body — the
 * consumer needs it to understand the rejection.</p>
 */
@Component
public class PushPayloadFactory {

    public PushPayload forSubmitted(ComplaintSubmittedEvent e) {
        return build(PushType.COMPLAINT_SUBMITTED, e.ticketNo(), e.complaintId(),
                "New complaint to triage",
                "Ticket " + e.ticketNo() + " awaits assignment in your DC");
    }

    public PushPayload forAssigned(ComplaintAssignedEvent e) {
        return build(PushType.COMPLAINT_ASSIGNED, e.ticketNo(), e.complaintId(),
                "New complaint assigned",
                "Ticket " + e.ticketNo() + " - " + e.severity() + " severity");
    }

    public PushPayload forReassigned(ComplaintReassignedEvent e) {
        return build(PushType.COMPLAINT_REASSIGNED, e.ticketNo(), e.complaintId(),
                "Complaint reassigned",
                "Ticket " + e.ticketNo() + " reassigned");
    }

    public PushPayload forResolved(ComplaintResolvedEvent e) {
        return build(PushType.COMPLAINT_RESOLVED, e.ticketNo(), e.complaintId(),
                "Complaint resolved",
                "Ticket " + e.ticketNo() + " has been marked resolved");
    }

    public PushPayload forClosed(ComplaintClosedEvent e) {
        return build(PushType.COMPLAINT_CLOSED, e.ticketNo(), e.complaintId(),
                "Complaint closed - rate your experience",
                "Ticket " + e.ticketNo() + " is now closed. Tap to share feedback.");
    }

    public PushPayload forSlaBreached(SlaBreachedEvent e) {
        return build(PushType.SLA_BREACHED, e.ticketNo(), e.complaintId(),
                "SLA breached",
                "Ticket " + e.ticketNo() + " has crossed its SLA deadline");
    }

    public PushPayload forFeedback(FeedbackSubmittedEvent e) {
        return build(PushType.FEEDBACK_RECEIVED, e.ticketNo(), e.complaintId(),
                "Feedback received",
                "Ticket " + e.ticketNo() + " rated " + e.rating() + "/5");
    }

    public PushPayload forCancelled(ComplaintCancelledEvent e) {
        return build(PushType.COMPLAINT_CANCELLED, e.ticketNo(), e.complaintId(),
                "Complaint cancelled",
                "Ticket " + e.ticketNo() + " cancelled by consumer");
    }

    public PushPayload forRejected(ComplaintRejectedEvent e) {
        String reason = (e.reason() == null || e.reason().isBlank()) ? "Not specified" : e.reason();
        return build(PushType.COMPLAINT_REJECTED, e.ticketNo(), e.complaintId(),
                "Complaint rejected",
                "Ticket " + e.ticketNo() + " was not accepted. Reason: " + reason);
    }

    private PushPayload build(PushType type, String ticketNo, Long complaintId, String title, String body) {
        OffsetDateTime now = DateUtils.toIst(Instant.now());
        return new PushPayload(type, ticketNo, complaintId, title, body, now,
                PushPayload.CURRENT_SCHEMA_VERSION);
    }
}

