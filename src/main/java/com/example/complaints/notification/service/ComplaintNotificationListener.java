package com.example.complaints.notification.service;

import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.auth.service.StaffScopeView;
import com.example.complaints.complaint.event.ComplaintAssignedEvent;
import com.example.complaints.complaint.event.ComplaintCancelledEvent;
import com.example.complaints.complaint.event.ComplaintClosedEvent;
import com.example.complaints.complaint.event.ComplaintReassignedEvent;
import com.example.complaints.complaint.event.ComplaintRejectedEvent;
import com.example.complaints.complaint.event.ComplaintResolvedEvent;
import com.example.complaints.complaint.event.ComplaintSubmittedEvent;
import com.example.complaints.complaint.event.FeedbackSubmittedEvent;
import com.example.complaints.complaint.event.SlaBreachedEvent;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.notification.dto.PushPayload;
import com.example.complaints.notification.model.DeviceToken;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Stage 21.2 — the nine {@code AFTER_COMMIT} listeners that turn
 * {@link com.example.complaints.complaint.event.ComplaintEvent} subtypes into push
 * notifications per the frozen contract §5.
 *
 * <p><b>AFTER_COMMIT</b> phase is mandatory: a transaction that publishes the event but
 * then rolls back must NOT result in a push (data inconsistency, lost notifications on
 * the FE side). Spring's {@code @TransactionalEventListener(phase = AFTER_COMMIT)} only
 * fires once the transaction commits successfully.</p>
 *
 * <p><b>Per-recipient failure isolation</b> per §6.1: every {@code push.send(...)} sits
 * inside a try / catch so one bad device never blocks the next. Transient failures are
 * logged and dropped (no inline retry — Stage 22+ concern). Permanent-failure handling
 * (flip {@code active=false}) lives in the FCM impl when it lands; the console impl
 * never fails.</p>
 *
 * <p>SLA breach fires <b>once per breach</b>, not per scheduler tick — the
 * {@link com.example.complaints.complaint.service.SlaMonitorService} only publishes on
 * the {@code false → true} transition, so this listener inherits the once-per-breach
 * semantics with zero local de-dup state.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplaintNotificationListener {

    private static final int LOW_RATING_THRESHOLD = 2;

    private final DeviceTokenRepository devices;
    private final PushService push;
    private final PushPayloadFactory payloads;
    private final StaffLookupService staff;
    private final DistributionCenterService dcs;

    // ---------- nine listeners, one per ComplaintEvent subtype ----------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(ComplaintSubmittedEvent e) {
        // §5: active engineer for the receiving DC
        PushPayload payload = payloads.forSubmitted(e);
        staff.findActiveEngineerForDc(e.distributionCenterId())
                .ifPresent(eng -> fanOutStaff(eng.userId(), payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssigned(ComplaintAssignedEvent e) {
        // §5: assigned technician; cc engineer if severity = HIGH (avoid noise on LOW/MEDIUM)
        PushPayload payload = payloads.forAssigned(e);
        fanOutStaff(e.assignedTechnicianId(), payload);
        if (e.severity() == ComplaintSeverity.HIGH) {
            fanOutStaff(e.assignedEngineerId(), payload);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReassigned(ComplaintReassignedEvent e) {
        // §5: new technician + previous technician + engineer
        PushPayload payload = payloads.forReassigned(e);
        fanOutStaff(e.assignedTechnicianId(), payload);
        fanOutStaff(e.previousTechnicianId(), payload);
        fanOutStaff(e.assignedEngineerId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(ComplaintResolvedEvent e) {
        // §5: consumer + engineer. SMS fallback for unregistered consumers is Stage 21.3+ behind a flag.
        PushPayload payload = payloads.forResolved(e);
        fanOutConsumer(e.consumerMasterId(), payload);
        fanOutStaff(e.assignedEngineerId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(ComplaintClosedEvent e) {
        // §5: consumer (nudges to feedback)
        PushPayload payload = payloads.forClosed(e);
        fanOutConsumer(e.consumerMasterId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaBreached(SlaBreachedEvent e) {
        // §5: assigned technician + assigned engineer. Once-per-breach guaranteed by the publisher.
        PushPayload payload = payloads.forSlaBreached(e);
        fanOutStaff(e.assignedTechnicianId(), payload);
        fanOutStaff(e.assignedEngineerId(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFeedback(FeedbackSubmittedEvent e) {
        // §5: assigned technician + engineer; admin escalation when rating ≤ 2
        PushPayload payload = payloads.forFeedback(e);
        fanOutStaff(e.assignedTechnicianId(), payload);
        fanOutStaff(e.assignedEngineerId(), payload);
        if (e.rating() <= LOW_RATING_THRESHOLD) {
            findAdminForDc(e.distributionCenterId())
                    .ifPresent(admin -> fanOutStaff(admin.userId(), payload));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(ComplaintCancelledEvent e) {
        // §5: assigned technician if any; else engineer of the DC (cancellation is SUBMITTED-only,
        // so there is no assigned technician — the engineer-of-DC branch always fires in v1).
        PushPayload payload = payloads.forCancelled(e);
        staff.findActiveEngineerForDc(e.distributionCenterId())
                .ifPresent(eng -> fanOutStaff(eng.userId(), payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRejected(ComplaintRejectedEvent e) {
        // §5: consumer (reason inlined in body)
        fanOutConsumer(e.consumerMasterId(), payloads.forRejected(e));
    }

    // ---------- fan-out helpers ----------

    private void fanOutStaff(Long userId, PushPayload payload) {
        if (userId == null) {
            return;
        }
        for (DeviceToken d : devices.findByUserIdAndActiveTrue(userId)) {
            sendIsolated(d, payload);
        }
    }

    private void fanOutConsumer(Long consumerMasterId, PushPayload payload) {
        if (consumerMasterId == null) {
            return;
        }
        for (DeviceToken d : devices.findByConsumerMasterIdAndActiveTrue(consumerMasterId)) {
            sendIsolated(d, payload);
        }
    }

    private void sendIsolated(DeviceToken d, PushPayload payload) {
        try {
            push.send(d, payload);
        } catch (RuntimeException ex) {
            // §6.1 per-recipient isolation: never let one bad device kill the rest of the fan-out.
            // §6.2 never-log fields: keep the failure log to the whitelist.
            log.warn("[PUSH] outcome=FAILED event={} ticketNo={} recipientUserId={} consumerMasterId={} platform={}",
                    payload.type(), payload.ticketNo(),
                    d.getUserId(), d.getConsumerMasterId(), d.getPlatform());
        }
    }

    /** Two-hop: DC → subdivisionId → admin. Returns empty for missing DC or no active admin. */
    private Optional<StaffScopeView> findAdminForDc(Long distributionCenterId) {
        if (distributionCenterId == null) {
            return Optional.empty();
        }
        Long subdivisionId = dcs.getSubdivisionId(distributionCenterId);
        return staff.findActiveAdminForSubdivision(subdivisionId);
    }
}

