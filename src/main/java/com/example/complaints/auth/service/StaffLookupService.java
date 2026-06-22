package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.StaffDirectoryEntryResponse;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Returns the directory entry for a single staff id, or throws
     * {@link ErrorCode#STAFF_NOT_FOUND}. Read-only and intentionally without scope filtering —
     * any authenticated staff member may resolve another's name (used by complaint history
     * timelines, picker dropdowns, etc.). Personal flags ({@code passwordResetRequired}) are
     * never exposed by this shape.
     */
    @Transactional(readOnly = true)
    public StaffDirectoryEntryResponse getDirectoryEntry(Long userId) {
        return toDirectoryEntry(users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_NOT_FOUND)));
    }

    /**
     * Batch variant: resolves the supplied ids in one round-trip. Unknown ids are silently
     * dropped (the caller may have stale IDs, e.g. a history row pointing at a deleted user
     * — though we never hard-delete today). Order of the returned list is not guaranteed.
     */
    @Transactional(readOnly = true)
    public List<StaffDirectoryEntryResponse> getDirectoryEntries(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, UserAccount> byId = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return userIds.stream()
                .distinct()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toDirectoryEntry)
                .toList();
    }

    private StaffDirectoryEntryResponse toDirectoryEntry(UserAccount u) {
        return new StaffDirectoryEntryResponse(u.getId(), u.getEmployeeId(), u.getFullName(),
                u.getRole(), u.getSubdivisionId(), u.getDistributionCenterId(), u.isEnabled());
    }

    /**
     * Paged directory search used by the FE technician / engineer picker dropdowns.
     *
     * <p>Scope rules (server-enforced; caller cannot escape them):</p>
     * <ul>
     *   <li><b>ADMIN</b> — results are filtered to the admin's subdivision. {@code distributionCenterId}
     *       may further narrow within the subdivision; if it's outside the subdivision the search
     *       simply returns empty (DC IDs are not secret, no need to 403-probe-detect).</li>
     *   <li><b>ENGINEER / TECHNICIAN</b> — results are pinned to the caller's DC. A supplied
     *       {@code distributionCenterId} that differs from the caller's DC → 403 {@code FORBIDDEN}
     *       (don't silently rewrite — we want the FE to know it's been overruled).</li>
     * </ul>
     *
     * <p>The narrower {@link StaffDirectoryEntryResponse} is returned (same shape as the
     * single / batch GETs) so cross-DC personal flags never leak.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<StaffDirectoryEntryResponse> searchDirectory(
            AuthenticatedStaff caller,
            UserRole role,
            Long distributionCenterId,
            Boolean active,
            Pageable pageable) {

        Long effectiveDcId = distributionCenterId;
        if (caller.role() != UserRole.ADMIN) {
            if (distributionCenterId != null
                    && !distributionCenterId.equals(caller.distributionCenterId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            effectiveDcId = caller.distributionCenterId();
        }

        Page<UserAccount> page = users.search(
                caller.subdivisionId(), role, effectiveDcId, active, pageable);
        return PageResponse.from(page.map(this::toDirectoryEntry));
    }
}


