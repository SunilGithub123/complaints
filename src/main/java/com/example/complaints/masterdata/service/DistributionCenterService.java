package com.example.complaints.masterdata.service;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.config.CaffeineCacheConfig;
import com.example.complaints.masterdata.dto.DistributionCenterRequest;
import com.example.complaints.masterdata.dto.DistributionCenterResponse;
import com.example.complaints.masterdata.mapper.DistributionCenterMapper;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DistributionCenterService {

    private final DistributionCenterRepository repo;
    private final DistributionCenterMapper mapper;
    private final SubdivisionService subdivisions;
    private final StaffLookupService staffLookup;

    @Transactional(readOnly = true)
    public PageResponse<DistributionCenterResponse> list(Long subdivisionId, Pageable pageable) {
        var page = (subdivisionId == null)
                ? repo.findAll(pageable)
                : repo.findBySubdivisionId(subdivisionId, pageable);
        return PageResponse.from(page.map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CaffeineCacheConfig.CACHE_DCS, key = "#id")
    public DistributionCenterResponse get(Long id) {
        return mapper.toResponse(load(id));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_DCS, allEntries = true)
    public DistributionCenterResponse create(AuthenticatedStaff me, DistributionCenterRequest req) {
        // Enforce scope: an admin can only create DCs under their own subdivision.
        requireSubdivisionInAdminScope(me, req.subdivisionId());
        subdivisions.requireActive(req.subdivisionId());
        if (repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.DC_CODE_TAKEN);
        }
        DistributionCenter dc = DistributionCenter.builder()
                .subdivisionId(req.subdivisionId())
                .code(req.code())
                .name(req.name())
                .address(req.address())
                .active(true)
                .build();
        return mapper.toResponse(repo.save(dc));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_DCS, allEntries = true)
    public DistributionCenterResponse update(AuthenticatedStaff me, Long id, DistributionCenterRequest req) {
        DistributionCenter dc = load(id);
        requireSubdivisionInAdminScope(me, dc.getSubdivisionId());
        requireSubdivisionInAdminScope(me, req.subdivisionId());
        subdivisions.requireActive(req.subdivisionId());
        if (!dc.getCode().equals(req.code()) && repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.DC_CODE_TAKEN);
        }
        dc.setSubdivisionId(req.subdivisionId());
        dc.setCode(req.code());
        dc.setName(req.name());
        dc.setAddress(req.address());
        return mapper.toResponse(dc);
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_DCS, allEntries = true)
    public DistributionCenterResponse setActive(AuthenticatedStaff me, Long id, boolean active) {
        DistributionCenter dc = load(id);
        requireSubdivisionInAdminScope(me, dc.getSubdivisionId());
        if (!active && dc.isActive() && staffLookup.hasActiveStaffInDistributionCenter(id)) {
            throw new BusinessException(ErrorCode.DC_HAS_ACTIVE_STAFF);
        }
        dc.setActive(active);
        return mapper.toResponse(dc);
    }

    /** Cross-module accessor used by complaint / user creation flows (Phases 1+). */
    @Transactional(readOnly = true)
    public DistributionCenter requireActive(Long id) {
        DistributionCenter dc = load(id);
        if (!dc.isActive()) {
            throw new BusinessException(ErrorCode.DC_INACTIVE);
        }
        return dc;
    }

    /**
     * Admin scope check: an ADMIN's subdivision must match the target.
     * The bootstrap admin's {@code subdivisionId} is always non-null (schema constraint).
     */
    private void requireSubdivisionInAdminScope(AuthenticatedStaff me, Long targetSubdivisionId) {
        if (me == null || me.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!me.subdivisionId().equals(targetSubdivisionId)) {
            throw new BusinessException(ErrorCode.DC_NOT_IN_SCOPE);
        }
    }

    private DistributionCenter load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DC_NOT_FOUND));
    }

    /**
     * Cross-module helper for the {@code complaint} module: returns the subdivision a DC belongs
     * to. Throws {@link ErrorCode#DC_NOT_FOUND} for unknown ids. Stays read-only so it composes
     * with caller transactions.
     */
    @Transactional(readOnly = true)
    public Long getSubdivisionId(Long distributionCenterId) {
        return load(distributionCenterId).getSubdivisionId();
    }
}

