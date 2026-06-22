package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.MarkDuplicateRequest;
import com.example.complaints.complaint.dto.RejectComplaintRequest;
import com.example.complaints.complaint.dto.UpdateSeverityRequest;
import com.example.complaints.complaint.event.ComplaintRejectedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.ComplaintStatusTransition;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engineer / Admin triage actions on a {@code SUBMITTED} complaint:
 * severity update, reject, mark-duplicate (TECHNICAL_DESIGN.md §5.4).
 *
 * <p>{@code updateSeverity} keeps the status; {@code reject} and {@code markDuplicate} are
 * state transitions out of {@code SUBMITTED} and consult {@link ComplaintStatusTransition}.
 * All paths are scoped via {@link ComplaintScopeGuard}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintTriageService {

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;
    private final ComplaintScopeGuard scope;

    private final ApplicationEventPublisher events;

    @Transactional
    public void updateSeverity(AuthenticatedStaff caller, Long complaintId, UpdateSeverityRequest req) {
        Complaint c = load(complaintId);
        scope.requireInScope(caller, c);
        // Severity can be revised at any non-terminal status. Refuse on terminal states so
        // the audit trail doesn't grow after the complaint is effectively closed.
        if (isTerminal(c.getStatus())) {
            throw new BusinessException(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
        }

        ComplaintSeverity previous = c.getSeverity();
        c.setSeverity(req.severity());
        appendHistory(c.getId(), c.getStatus(), c.getStatus(), caller.userId(),
                "Severity changed from " + previous + " to " + req.severity());
        log.info("Updated severity of complaint {} from {} to {} by user {}",
                c.getId(), previous, req.severity(), caller.userId());
    }

    @Transactional
    public void reject(AuthenticatedStaff caller, Long complaintId, RejectComplaintRequest req) {
        Complaint c = load(complaintId);
        scope.requireInScope(caller, c);
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.REJECTED);

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.REJECTED);
        c.setRejectionReason(req.reason());
        appendHistory(c.getId(), previous, ComplaintStatus.REJECTED, caller.userId(), req.reason());
        events.publishEvent(new ComplaintRejectedEvent(
                c.getId(), c.getTicketNo(), c.getConsumerMasterId(),
                c.getDistributionCenterId(), req.reason(), caller.userId()));
        log.info("Rejected complaint {} by user {}", c.getId(), caller.userId());
    }

    @Transactional
    public void markDuplicate(AuthenticatedStaff caller, Long complaintId, MarkDuplicateRequest req) {
        Complaint c = load(complaintId);
        scope.requireInScope(caller, c);
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.DUPLICATE);

        Complaint parent = complaints.findByTicketNo(req.parentTicketNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARENT_COMPLAINT_NOT_FOUND));
        if (parent.getId().equals(c.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OF_SELF);
        }
        if (parent.getStatus() == ComplaintStatus.DUPLICATE
                || parent.getStatus() == ComplaintStatus.REJECTED) {
            throw new BusinessException(ErrorCode.DUPLICATE_PARENT_INVALID);
        }

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.DUPLICATE);
        c.setParentComplaintId(parent.getId());
        String note = "Marked duplicate of " + parent.getTicketNo()
                + (req.reason() == null || req.reason().isBlank() ? "" : ": " + req.reason());
        appendHistory(c.getId(), previous, ComplaintStatus.DUPLICATE, caller.userId(), note);
        log.info("Marked complaint {} duplicate of {} by user {}",
                c.getId(), parent.getId(), caller.userId());
    }

    private boolean isTerminal(ComplaintStatus s) {
        return s == ComplaintStatus.RESOLVED || s == ComplaintStatus.CLOSED
                || s == ComplaintStatus.CANCELLED || s == ComplaintStatus.REJECTED
                || s == ComplaintStatus.DUPLICATE;
    }

    private Complaint load(Long id) {
        return complaints.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
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

