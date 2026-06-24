package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.event.ComplaintClosedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.ComplaintStatusTransition;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Complaint closure (TECHNICAL_DESIGN.md §5.4, BRD §4.8). Two entry-points share one
 * underlying closure routine:
 * <ul>
 *   <li>{@link #close(AuthenticatedStaff, Long, CloseComplaintRequest)} — Engineer / Admin
 *       close-on-behalf, scoped via {@link ComplaintScopeGuard} (engineer DC / admin
 *       subdivision).</li>
 *   <li>{@link #closeByTechnician(AuthenticatedStaff, Long, CloseComplaintRequest)} — the
 *       <em>normal</em> closer per BRD §4.8. Scoped to "I am the assigned technician".</li>
 * </ul>
 *
 * <p>Both routes (a) require the complaint to be {@code RESOLVED} (state machine refuses
 * {@code SUBMITTED → CLOSED} or {@code IN_PROGRESS → CLOSED} short-cuts in v1), (b) flip
 * {@code sla_breached = true} when {@code now > sla_deadline}, and (c) require
 * {@code slaBreachReason} from <em>whoever closes</em> when breached and no reason was
 * captured at resolve time. Cancellation of an un-started complaint is the consumer-side
 * {@code CANCELLED} path (Phase 5).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintClosureService {

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;
    private final ComplaintScopeGuard scope;
    private final ApplicationEventPublisher events;

    /**
     * Engineer / Admin close-on-behalf path. See class Javadoc.
     */
    @Transactional
    public void close(AuthenticatedStaff caller, Long complaintId, CloseComplaintRequest req) {
        Complaint c = complaints.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        scope.requireInScope(caller, c);
        doClose(c, caller, req);
    }

    /**
     * Technician close path — BRD §4.8 "Technician is the normal closing actor". The caller
     * must be the {@code assigned_technician_id} on the complaint; otherwise
     * {@link ErrorCode#COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN}. Same SLA-breach-reason rule
     * as the staff path; same state-machine restriction ({@code RESOLVED → CLOSED} only).
     */
    @Transactional
    public void closeByTechnician(AuthenticatedStaff caller, Long complaintId, CloseComplaintRequest req) {
        Complaint c = complaints.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (c.getAssignedTechnicianId() == null
                || !c.getAssignedTechnicianId().equals(caller.userId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN);
        }
        doClose(c, caller, req);
    }

    private void doClose(Complaint c, AuthenticatedStaff caller, CloseComplaintRequest req) {
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.CLOSED);

        // SLA enforcement: prefer the captured breach state from resolve(); also recompute
        // against deadline in case close happens after a still-on-time resolve but past the SLA.
        Instant now = Instant.now();
        boolean breached = c.isSlaBreached() || now.isAfter(c.getSlaDeadline());
        boolean reasonAlreadyOnFile = c.getSlaBreachReason() != null && !c.getSlaBreachReason().isBlank();
        if (breached && !reasonAlreadyOnFile
                && (req.slaBreachReason() == null || req.slaBreachReason().isBlank())) {
            throw new BusinessException(ErrorCode.SLA_BREACH_REASON_REQUIRED);
        }

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.CLOSED);
        c.setClosedAt(now);
        if (breached) {
            c.setSlaBreached(true);
            if (!reasonAlreadyOnFile) {
                c.setSlaBreachReason(req.slaBreachReason());
            }
        }
        appendHistory(c.getId(), previous, ComplaintStatus.CLOSED, caller.userId(),
                breached ? "Closed (SLA breached)" : "Closed");
        events.publishEvent(new ComplaintClosedEvent(
                c.getId(), c.getTicketNo(), c.getConsumerMasterId(),
                c.getAssignedTechnicianId(), c.getAssignedEngineerId(),
                c.isSlaBreached(), c.getClosedAt(), caller.userId()));
        log.info("User {} closed complaint {} (breached={})", caller.userId(), c.getId(), breached);
    }

    private void appendHistory(Long complaintId, ComplaintStatus from, ComplaintStatus to,
                               Long actorUserId, String note) {
        history.save(ComplaintHistory.builder()
                .complaintId(complaintId)
                .fromStatus(from)
                .toStatus(to)
                .changedByUserId(actorUserId)
                .note(note)
                .build());
    }
}

