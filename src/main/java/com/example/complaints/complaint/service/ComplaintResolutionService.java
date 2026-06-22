package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintImageResponse;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.event.ComplaintResolvedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.ComplaintStatusTransition;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/**
 * Technician-driven lifecycle: start (ASSIGNED → IN_PROGRESS), resolve (IN_PROGRESS → RESOLVED),
 * and resolution-image upload (state unchanged). See TECHNICAL_DESIGN.md §5.5.
 *
 * <p>Scope: every method here enforces
 * {@code complaint.assignedTechnicianId == caller.userId()} — i.e. the technician can only
 * touch complaints assigned to them. Cross-technician operations (engineer reassign, admin
 * cross-DC reassign) live in {@link ComplaintAssignmentService}.</p>
 *
 * <p>SLA enforcement on {@code resolve}: if {@code Instant.now()} is past
 * {@code complaint.slaDeadline}, the request must carry a non-blank {@code slaBreachReason};
 * otherwise the call fails with {@code SLA_BREACH_REASON_REQUIRED}. When the breach is
 * detected the service also flips {@code slaBreached = true} (idempotent — Stage 15's scheduler
 * may have already done it).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintResolutionService {

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;
    private final ComplaintImageService imageService;
    private final ComplaintMapper mapper;
    private final ApplicationEventPublisher events;

    @Transactional
    public void start(AuthenticatedStaff caller, Long complaintId) {
        Complaint c = loadOwnedBy(caller, complaintId);
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.IN_PROGRESS);

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.IN_PROGRESS);
        appendHistory(c.getId(), previous, ComplaintStatus.IN_PROGRESS, caller.userId(),
                "Technician started work");
        log.info("Technician {} started complaint {}", caller.userId(), c.getId());
    }

    @Transactional
    public void resolve(AuthenticatedStaff caller, Long complaintId, ResolveComplaintRequest req) {
        Complaint c = loadOwnedBy(caller, complaintId);
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.RESOLVED);

        Instant now = Instant.now();
        boolean breached = now.isAfter(c.getSlaDeadline());
        if (breached && (req.slaBreachReason() == null || req.slaBreachReason().isBlank())) {
            throw new BusinessException(ErrorCode.SLA_BREACH_REASON_REQUIRED);
        }

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.RESOLVED);
        c.setResolutionNotes(req.resolutionNotes());
        c.setResolvedAt(now);
        if (breached) {
            c.setSlaBreached(true);
            c.setSlaBreachReason(req.slaBreachReason());
        }
        appendHistory(c.getId(), previous, ComplaintStatus.RESOLVED, caller.userId(),
                breached ? "Resolved (SLA breached): " + req.slaBreachReason() : "Resolved");
        events.publishEvent(new ComplaintResolvedEvent(
                c.getId(), c.getTicketNo(), c.getConsumerMasterId(),
                c.getAssignedTechnicianId(), c.getAssignedEngineerId(),
                c.isSlaBreached(), c.getResolvedAt(), caller.userId()));
        log.info("Technician {} resolved complaint {} (breached={})",
                caller.userId(), c.getId(), breached);
    }

    @Transactional
    public List<ComplaintImageResponse> addResolutionImages(AuthenticatedStaff caller,
                                                            Long complaintId,
                                                            List<MultipartFile> files) {
        Complaint c = loadOwnedBy(caller, complaintId);
        // Resolution images only make sense once work has started — and once the complaint is
        // closed the audit trail is sealed. RESOLVED accepts late additions while the engineer
        // verifies before closing.
        if (c.getStatus() != ComplaintStatus.IN_PROGRESS && c.getStatus() != ComplaintStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
        }
        List<ComplaintImage> saved = imageService.storeResolutionImages(c.getId(), files, caller.userId());
        return saved.stream().map(mapper::toImageResponse).toList();
    }

    private Complaint loadOwnedBy(AuthenticatedStaff caller, Long complaintId) {
        Complaint c = complaints.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (c.getAssignedTechnicianId() == null
                || !c.getAssignedTechnicianId().equals(caller.userId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN);
        }
        return c;
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

