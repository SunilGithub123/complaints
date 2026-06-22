package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.masterdata.service.DistributionCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 4 scope-check helper shared by {@code ComplaintAssignmentService} and
 * {@code ComplaintTriageService} (and, in Stage 14, the closure / resolution services).
 *
 * <p>Encodes the two scope rules from TECHNICAL_DESIGN.md §5.4:</p>
 * <ul>
 *   <li><b>Engineer</b> may touch only complaints whose {@code distribution_center_id} matches
 *       the engineer's DC.</li>
 *   <li><b>Admin</b> may touch only complaints whose DC belongs to the admin's subdivision.</li>
 * </ul>
 *
 * <p>Extracted on day-one (rather than waiting for "the second use") because two services land
 * in the same stage and the check is non-trivial — admin scope requires a DC → subdivision
 * lookup that we don't want to inline-duplicate.</p>
 */
@Component
@RequiredArgsConstructor
public class ComplaintScopeGuard {

    private final DistributionCenterService dcs;

    /**
     * Throws {@link ErrorCode#COMPLAINT_OUT_OF_SCOPE} if {@code caller} is not allowed to touch
     * {@code complaint}. Returns silently otherwise. Throws {@link ErrorCode#FORBIDDEN} for
     * unexpected roles (e.g. technician hitting a staff-engineer endpoint).
     */
    public void requireInScope(AuthenticatedStaff caller, Complaint complaint) {
        Long complaintDcId = complaint.getDistributionCenterId();
        if (caller.role() == UserRole.ENGINEER) {
            if (complaintDcId == null || !complaintDcId.equals(caller.distributionCenterId())) {
                throw new BusinessException(ErrorCode.COMPLAINT_OUT_OF_SCOPE);
            }
            return;
        }
        if (caller.role() == UserRole.ADMIN) {
            if (complaintDcId == null) {
                throw new BusinessException(ErrorCode.COMPLAINT_OUT_OF_SCOPE);
            }
            Long complaintSubdivisionId = dcs.getSubdivisionId(complaintDcId);
            if (!complaintSubdivisionId.equals(caller.subdivisionId())) {
                throw new BusinessException(ErrorCode.COMPLAINT_OUT_OF_SCOPE);
            }
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}

