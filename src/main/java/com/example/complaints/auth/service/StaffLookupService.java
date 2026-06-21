package com.example.complaints.auth.service;

import com.example.complaints.auth.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin read-only entry point for other modules that need to ask
 * "are there any active staff under {subdivision|DC}?" without depending
 * on the {@code auth} repository directly (cross-module repository access is
 * forbidden — see {@code PackageBoundaryTest}).
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
}

