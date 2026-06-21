package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.CreateStaffRequest;
import com.example.complaints.auth.dto.ResetStaffPasswordResponse;
import com.example.complaints.auth.dto.StaffListItemResponse;
import com.example.complaints.auth.dto.UpdateStaffRequest;
import com.example.complaints.auth.mapper.UserAccountMapper;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.RefreshTokenRepository;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.masterdata.service.SubdivisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Admin → staff user management.
 *
 * <p>Caller scope: every method assumes the caller is an ADMIN whose
 * {@link AuthenticatedStaff#subdivisionId()} bounds what they may touch.
 * Cross-subdivision attempts → {@link ErrorCode#STAFF_SCOPE_MISMATCH}.</p>
 *
 * <p>Business invariants enforced (matching the DB partial-unique indexes
 * declared in {@code V1.0__init_schema.sql} — we duplicate them here so the
 * caller gets a clean {@link BusinessException} instead of a
 * {@code DataIntegrityViolationException}):</p>
 * <ul>
 *   <li>ADMIN: at most one active per subdivision; DC must be {@code null}.</li>
 *   <li>ENGINEER: at most one active per DC; DC required.</li>
 *   <li>TECHNICIAN: many per DC allowed; DC required.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAdminService {

    /**
     * Temp-password alphabet — readable on a phone screen, no ambiguous chars
     * (no 0/O, 1/l/I), mixes case + a special. 16 chars meets the FE's min-12
     * rule for the subsequent change-password call.
     */
    private static final char[] TEMP_PWD_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789@#$%".toCharArray();
    private static final int TEMP_PWD_LENGTH = 16;

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final UserAccountMapper mapper;
    private final SubdivisionService subdivisions;
    private final DistributionCenterService distributionCenters;
    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public PageResponse<StaffListItemResponse> list(
            AuthenticatedStaff me,
            UserRole role,
            Long distributionCenterId,
            Boolean enabled,
            Pageable pageable) {
        requireAdmin(me);
        var page = users.search(me.subdivisionId(), role, distributionCenterId, enabled, pageable);
        return PageResponse.from(page.map(mapper::toListItem));
    }

    @Transactional(readOnly = true)
    public StaffListItemResponse get(AuthenticatedStaff me, Long id) {
        requireAdmin(me);
        UserAccount staff = loadInScope(me, id);
        return mapper.toListItem(staff);
    }

    @Transactional
    public ResetStaffPasswordResponse create(AuthenticatedStaff me, CreateStaffRequest req) {
        requireAdmin(me);
        requireSubdivisionInScope(me, req.subdivisionId());
        subdivisions.requireActive(req.subdivisionId());
        validateRoleScopeFields(req.role(), req.distributionCenterId());
        if (req.distributionCenterId() != null) {
            DistributionCenter dc = distributionCenters.requireActive(req.distributionCenterId());
            if (!dc.getSubdivisionId().equals(req.subdivisionId())) {
                throw new BusinessException(ErrorCode.DC_NOT_IN_SUBDIVISION);
            }
        }
        if (users.existsByEmployeeId(req.employeeId())) {
            throw new BusinessException(ErrorCode.EMPLOYEE_ID_TAKEN);
        }
        requireRoleUniquenessForNewStaff(req.role(), req.subdivisionId(), req.distributionCenterId());

        String temp = generateTemporaryPassword();
        UserAccount entity = UserAccount.builder()
                .employeeId(req.employeeId())
                .passwordHash(passwordEncoder.encode(temp))
                .passwordResetRequired(true)
                .role(req.role())
                .fullName(req.fullName())
                .email(req.email())
                .mobile(req.mobile())
                .subdivisionId(req.subdivisionId())
                .distributionCenterId(req.distributionCenterId())
                .createdByUserId(me.userId())
                .enabled(true)
                .notificationsPushEnabled(true)
                .build();
        UserAccount saved = users.save(entity);
        log.info("Admin {} created staff {} (role {}, subdivision {})",
                me.userId(), saved.getId(), saved.getRole(), saved.getSubdivisionId());
        return new ResetStaffPasswordResponse(saved.getId(), saved.getEmployeeId(), temp);
    }

    @Transactional
    public StaffListItemResponse update(AuthenticatedStaff me, Long id, UpdateStaffRequest req) {
        requireAdmin(me);
        UserAccount staff = loadInScope(me, id);
        validateRoleScopeFields(staff.getRole(), req.distributionCenterId());
        if (req.distributionCenterId() != null
                && !req.distributionCenterId().equals(staff.getDistributionCenterId())) {
            DistributionCenter dc = distributionCenters.requireActive(req.distributionCenterId());
            if (!dc.getSubdivisionId().equals(staff.getSubdivisionId())) {
                throw new BusinessException(ErrorCode.DC_NOT_IN_SUBDIVISION);
            }
            // Re-check the per-DC uniqueness if the role demands it.
            if (staff.getRole() == UserRole.ENGINEER && staff.isEnabled()
                    && users.existsByRoleAndDistributionCenterIdAndEnabledTrue(
                            UserRole.ENGINEER, req.distributionCenterId())) {
                throw new BusinessException(ErrorCode.ENGINEER_ALREADY_EXISTS_FOR_DC);
            }
        }
        staff.setFullName(req.fullName());
        staff.setEmail(req.email());
        staff.setMobile(req.mobile());
        staff.setDistributionCenterId(req.distributionCenterId());
        return mapper.toListItem(staff);
    }

    @Transactional
    public StaffListItemResponse setActive(AuthenticatedStaff me, Long id, boolean active) {
        requireAdmin(me);
        UserAccount staff = loadInScope(me, id);
        if (!active && me.userId().equals(staff.getId())) {
            throw new BusinessException(ErrorCode.CANNOT_DEACTIVATE_SELF);
        }
        if (active && !staff.isEnabled()) {
            requireRoleUniquenessForReactivation(staff);
        }
        if (!active && staff.isEnabled()) {
            // Revoke any live sessions the moment we disable the account.
            refreshTokens.revokeAllForUser(staff.getId());
        }
        staff.setEnabled(active);
        return mapper.toListItem(staff);
    }

    @Transactional
    public ResetStaffPasswordResponse resetPassword(AuthenticatedStaff me, Long id) {
        requireAdmin(me);
        UserAccount staff = loadInScope(me, id);
        if (me.userId().equals(staff.getId())) {
            // Admins change their own password via /staff/auth/change-password.
            throw new BusinessException(ErrorCode.CANNOT_DEACTIVATE_SELF);
        }
        String temp = generateTemporaryPassword();
        staff.setPasswordHash(passwordEncoder.encode(temp));
        staff.setPasswordResetRequired(true);
        refreshTokens.revokeAllForUser(staff.getId());
        log.info("Admin {} reset password for staff {}", me.userId(), staff.getId());
        return new ResetStaffPasswordResponse(staff.getId(), staff.getEmployeeId(), temp);
    }

    // ----- helpers -----

    private void requireAdmin(AuthenticatedStaff me) {
        if (me == null || me.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireSubdivisionInScope(AuthenticatedStaff me, Long targetSubdivisionId) {
        if (!me.subdivisionId().equals(targetSubdivisionId)) {
            throw new BusinessException(ErrorCode.STAFF_SCOPE_MISMATCH);
        }
    }

    private UserAccount loadInScope(AuthenticatedStaff me, Long id) {
        UserAccount staff = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_NOT_FOUND));
        requireSubdivisionInScope(me, staff.getSubdivisionId());
        return staff;
    }

    private void validateRoleScopeFields(UserRole role, Long distributionCenterId) {
        boolean needsDc = role == UserRole.ENGINEER || role == UserRole.TECHNICIAN;
        boolean hasDc = distributionCenterId != null;
        if (needsDc != hasDc) {
            throw new BusinessException(ErrorCode.STAFF_ROLE_FIELDS_INVALID);
        }
    }

    private void requireRoleUniquenessForNewStaff(UserRole role, Long subdivisionId, Long dcId) {
        if (role == UserRole.ADMIN
                && users.existsByRoleAndSubdivisionIdAndEnabledTrue(UserRole.ADMIN, subdivisionId)) {
            throw new BusinessException(ErrorCode.ADMIN_ALREADY_EXISTS_FOR_SUBDIV);
        }
        if (role == UserRole.ENGINEER
                && users.existsByRoleAndDistributionCenterIdAndEnabledTrue(UserRole.ENGINEER, dcId)) {
            throw new BusinessException(ErrorCode.ENGINEER_ALREADY_EXISTS_FOR_DC);
        }
    }

    private void requireRoleUniquenessForReactivation(UserAccount staff) {
        if (staff.getRole() == UserRole.ADMIN
                && users.existsByRoleAndSubdivisionIdAndEnabledTrue(
                        UserRole.ADMIN, staff.getSubdivisionId())) {
            throw new BusinessException(ErrorCode.ADMIN_ALREADY_EXISTS_FOR_SUBDIV);
        }
        if (staff.getRole() == UserRole.ENGINEER
                && users.existsByRoleAndDistributionCenterIdAndEnabledTrue(
                        UserRole.ENGINEER, staff.getDistributionCenterId())) {
            throw new BusinessException(ErrorCode.ENGINEER_ALREADY_EXISTS_FOR_DC);
        }
    }

    private String generateTemporaryPassword() {
        char[] buf = new char[TEMP_PWD_LENGTH];
        for (int i = 0; i < TEMP_PWD_LENGTH; i++) {
            buf[i] = TEMP_PWD_ALPHABET[random.nextInt(TEMP_PWD_ALPHABET.length)];
        }
        return new String(buf);
    }
}

