package com.example.complaints.complaint.event;

/**
 * Marker for every complaint-lifecycle domain event (Stage 20). Sealed so the set of permitted
 * event types is exhaustive and a listener can switch on it without a wildcard branch.
 *
 * <p><b>Contract for publishers</b> (see {@code ApplicationEventPublisher.publishEvent}):</p>
 * <ul>
 *   <li>Publish from inside the service method that mutated state, <b>after</b> the in-memory
 *       mutation is complete but before the method returns. The actual delivery is gated by
 *       {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — a rolled-back tx fires
 *       nothing.</li>
 *   <li>The payload must be self-contained: primitive fields + ids only, never a JPA entity.
 *       AFTER_COMMIT listeners run outside the original transaction; lazy collections would
 *       blow up, and crossing a module boundary with an entity violates ArchUnit.</li>
 * </ul>
 *
 * <p><b>Contract for listeners</b>: use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * unless you have a specific reason to participate in the publisher's transaction. If the
 * listener needs richer data than the payload exposes, re-read it via a thin
 * query-service in your own module — do not reach into another module's repository.</p>
 */
public sealed interface ComplaintEvent
        permits ComplaintSubmittedEvent,
                ComplaintAssignedEvent,
                ComplaintReassignedEvent,
                ComplaintResolvedEvent,
                ComplaintClosedEvent,
                ComplaintCancelledEvent,
                ComplaintRejectedEvent,
                SlaBreachedEvent,
                FeedbackSubmittedEvent {

    Long complaintId();

    String ticketNo();
}

