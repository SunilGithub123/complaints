package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.auth.service.StaffScopeView;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.ReassignComplaintRequest;
import com.example.complaints.complaint.event.ComplaintAssignedEvent;
import com.example.complaints.complaint.event.ComplaintReassignedEvent;
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

/**
 * Engineer / Admin complaint assignment + reassignment. See TECHNICAL_DESIGN.md §5.4.
 *
 * <p>Scope rules:</p>
 * <ul>
 *   <li><b>assign</b> — complaint must be in caller's scope (engineer DC / admin subdivision)
 *       AND the chosen technician must be reachable from the caller:
 *       <ul>
 *         <li>Engineer: technician.dcId == engineer.dcId</li>
 *         <li>Admin: technician.subdivisionId == admin.subdivisionId</li>
 *       </ul>
 *   </li>
 *   <li><b>reassign</b> — same scope rules; when an admin picks a technician in a DIFFERENT DC,
 *       the complaint's {@code distribution_center_id} and {@code assigned_engineer_id} are
 *       re-pointed to the new DC's active engineer.</li>
 * </ul>
 *
 * <p>Status transitions go through {@link ComplaintStatusTransition} (assign:
 * SUBMITTED → ASSIGNED). Reassignment is a same-state edit and deliberately does not consult the
 * validator. Optimistic-lock contention surfaces as {@code COMPLAINT_VERSION_CONFLICT} via the
 * global handler.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintAssignmentService {

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;
    private final ComplaintScopeGuard scope;
    private final StaffLookupService staff;

    private final ApplicationEventPublisher events;

    @Transactional
    public void assign(AuthenticatedStaff caller, Long complaintId, AssignComplaintRequest req) {
        Complaint c = load(complaintId);
        scope.requireInScope(caller, c);
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.ASSIGNED);

        StaffScopeView tech = staff.getActiveTechnician(req.technicianId());
        requireTechnicianReachable(caller, tech);

        ComplaintStatus previous = c.getStatus();
        c.setAssignedTechnicianId(tech.userId());
        c.setSeverity(req.severity());
        c.setStatus(ComplaintStatus.ASSIGNED);

        // Engineer FK: engineer caller assigns to a technician in their own DC, so the caller
        // IS the engineer. Admin caller: look up the active engineer for the technician's DC.
        Long engineerId = (caller.role() == UserRole.ENGINEER)
                ? caller.userId()
                : staff.findActiveEngineerForDc(tech.distributionCenterId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_ENGINEER_FOR_DC))
                        .userId();
        c.setAssignedEngineerId(engineerId);

        appendHistory(c.getId(), previous, ComplaintStatus.ASSIGNED, caller.userId(),
                "Assigned to technician " + tech.userId() + " with severity " + req.severity());
        events.publishEvent(new ComplaintAssignedEvent(
                c.getId(), c.getTicketNo(), tech.userId(), engineerId,
                c.getDistributionCenterId(), req.severity(), caller.userId()));
        log.info("Assigned complaint {} to technician {} (engineer {}) by user {}",
                c.getId(), tech.userId(), engineerId, caller.userId());
    }

    @Transactional
    public void reassign(AuthenticatedStaff caller, Long complaintId, ReassignComplaintRequest req) {
        Complaint c = load(complaintId);
        scope.requireInScope(caller, c);
        // Only post-assignment states can be reassigned. SUBMITTED still wants /assign;
        // terminal/closed states refuse. Encoded as: must currently have a technician.
        if (c.getAssignedTechnicianId() == null) {
            throw new BusinessException(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
        }
        if (c.getStatus() == ComplaintStatus.RESOLVED
                || c.getStatus() == ComplaintStatus.CLOSED
                || c.getStatus() == ComplaintStatus.CANCELLED
                || c.getStatus() == ComplaintStatus.REJECTED
                || c.getStatus() == ComplaintStatus.DUPLICATE) {
            throw new BusinessException(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
        }

        StaffScopeView tech = staff.getActiveTechnician(req.technicianId());
        requireTechnicianReachable(caller, tech);

        Long previousTech = c.getAssignedTechnicianId();
        Long previousDc = c.getDistributionCenterId();
        c.setAssignedTechnicianId(tech.userId());

        // Admin cross-DC: re-point DC + engineer.
        if (!tech.distributionCenterId().equals(c.getDistributionCenterId())) {
            // Engineer caller should never hit this branch (requireTechnicianReachable enforces
            // same-DC for engineers); admin can.
            c.setDistributionCenterId(tech.distributionCenterId());
            c.setAssignedEngineerId(staff.findActiveEngineerForDc(tech.distributionCenterId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_ENGINEER_FOR_DC))
                    .userId());
        }

        String note = "Reassigned from technician " + previousTech + " to " + tech.userId()
                + (previousDc.equals(tech.distributionCenterId()) ? "" : " (DC changed)")
                + (req.reason() == null || req.reason().isBlank() ? "" : ": " + req.reason());
        appendHistory(c.getId(), c.getStatus(), c.getStatus(), caller.userId(), note);
        events.publishEvent(new ComplaintReassignedEvent(
                c.getId(), c.getTicketNo(), previousTech, tech.userId(),
                previousDc, c.getDistributionCenterId(), c.getAssignedEngineerId(),
                caller.userId(), req.reason()));
        log.info("Reassigned complaint {} from technician {} to {} by user {}",
                c.getId(), previousTech, tech.userId(), caller.userId());
    }

    private void requireTechnicianReachable(AuthenticatedStaff caller, StaffScopeView tech) {
        if (caller.role() == UserRole.ENGINEER) {
            if (tech.distributionCenterId() == null
                    || !tech.distributionCenterId().equals(caller.distributionCenterId())) {
                throw new BusinessException(ErrorCode.TECHNICIAN_NOT_IN_DC);
            }
            return;
        }
        if (caller.role() == UserRole.ADMIN) {
            if (tech.subdivisionId() == null
                    || !tech.subdivisionId().equals(caller.subdivisionId())) {
                throw new BusinessException(ErrorCode.TECHNICIAN_NOT_IN_DC);
            }
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
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

