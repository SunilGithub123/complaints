package com.example.complaints.auth.service;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Thin read-only entry point for other modules that need to ask scope questions about staff
 * ("are there any active staff under {subdivision|DC}?", "who's the active engineer for this
 * DC?", "is this technician active?") without depending on the {@code auth} repository directly
 * (cross-module repository access is forbidden — see {@code PackageBoundaryTest}).
 *
 * <p>Lives in its own service rather than on {@link StaffAdminService} to
 * break the dependency cycle: {@code StaffAdminService} already depends on
 * {@code masterdata.SubdivisionService} / {@code DistributionCenterService};
 * if those started depending on {@code StaffAdminService} the bean graph
 * would not bootstrap.</p>
 */
@Service
@RequiredArgsConstructor
public class StaffLookupService {

    private final UserAccountRepository users;

    @Transactional(readOnly = true)
    public boolean hasActiveStaffInSubdivision(Long subdivisionId) {
        return users.existsBySubdivisionIdAndEnabledTrue(subdivisionId);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveStaffInDistributionCenter(Long distributionCenterId) {
        return users.existsByDistributionCenterIdAndEnabledTrue(distributionCenterId);
    }

    /**
     * Returns the active technician identified by {@code technicianId}, or throws
     * {@link ErrorCode#TECHNICIAN_NOT_FOUND} if the user does not exist, is disabled, or is not
     * a {@link UserRole#TECHNICIAN}. Used by assignment + reassignment flows.
     */
    @Transactional(readOnly = true)
    public StaffScopeView getActiveTechnician(Long technicianId) {
        UserAccount u = users.findById(technicianId)
                .filter(UserAccount::isEnabled)
                .filter(x -> x.getRole() == UserRole.TECHNICIAN)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECHNICIAN_NOT_FOUND));
        return toView(u);
    }

    /**
     * Locates the active engineer for {@code distributionCenterId} (there is at most one — the
     * partial-unique index {@code uq_one_active_engineer_per_dc} enforces this). Empty if the DC
     * has no active engineer; callers decide whether that is an error.
     */
    @Transactional(readOnly = true)
    public Optional<StaffScopeView> findActiveEngineerForDc(Long distributionCenterId) {
        return users.findFirstByRoleAndDistributionCenterIdAndEnabledTrue(
                        UserRole.ENGINEER, distributionCenterId)
                .map(this::toView);
    }

    private StaffScopeView toView(UserAccount u) {
        return new StaffScopeView(u.getId(), u.getRole(),
                u.getSubdivisionId(), u.getDistributionCenterId(), u.isEnabled());
    }
}


