package com.example.complaints.complaint.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stage 20 debug listener — logs every {@link ComplaintEvent} after the publisher's transaction
 * commits, so we can prove end-to-end wiring without yet having a real notification dispatcher.
 *
 * <p>Two listener methods on purpose:</p>
 * <ul>
 *   <li>{@link #onCommittedEvent(ComplaintEvent)} — the main, production-shaped listener with
 *       {@code AFTER_COMMIT} semantics. A rolled-back tx fires nothing.</li>
 *   <li>{@link #onAnyEvent(ComplaintEvent)} — synchronous listener at {@code DEBUG} level so a
 *       developer can confirm publish-time wiring even when the tx hasn't committed yet
 *       (useful when investigating "did the service actually publish this?" in tests).</li>
 * </ul>
 *
 * <p>Both will be replaced by per-event listeners in the {@code notification} module in Stage
 * 21; this class is intentionally a thin layer that costs nothing to remove.</p>
 */
@Component
@Slf4j
public class ComplaintEventLogger {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommittedEvent(ComplaintEvent event) {
        log.info("Complaint event delivered (AFTER_COMMIT): {} ticket={} id={}",
                event.getClass().getSimpleName(), event.ticketNo(), event.complaintId());
    }

    @EventListener
    public void onAnyEvent(ComplaintEvent event) {
        log.debug("Complaint event published (pre-commit): {} ticket={} id={}",
                event.getClass().getSimpleName(), event.ticketNo(), event.complaintId());
    }
}

