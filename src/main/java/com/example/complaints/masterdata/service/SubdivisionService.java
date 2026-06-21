package com.example.complaints.masterdata.service;

import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.config.CaffeineCacheConfig;
import com.example.complaints.masterdata.dto.SubdivisionRequest;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.mapper.SubdivisionMapper;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import com.example.complaints.masterdata.repository.SubdivisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubdivisionService {

    private final SubdivisionRepository repo;
    private final SubdivisionMapper mapper;
    private final DistributionCenterRepository distributionCenters;
    private final StaffLookupService staffLookup;

    @Transactional(readOnly = true)
    public PageResponse<SubdivisionResponse> list(Pageable pageable) {
        return PageResponse.from(repo.findAll(pageable).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CaffeineCacheConfig.CACHE_SUBDIVISIONS, key = "#id")
    public SubdivisionResponse get(Long id) {
        return mapper.toResponse(load(id));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_SUBDIVISIONS, allEntries = true)
    public SubdivisionResponse create(SubdivisionRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.SUBDIVISION_CODE_TAKEN);
        }
        Subdivision s = Subdivision.builder()
                .code(req.code())
                .name(req.name())
                .district(req.district())
                .active(true)
                .build();
        return mapper.toResponse(repo.save(s));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_SUBDIVISIONS, allEntries = true)
    public SubdivisionResponse update(Long id, SubdivisionRequest req) {
        Subdivision s = load(id);
        if (!s.getCode().equals(req.code()) && repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.SUBDIVISION_CODE_TAKEN);
        }
        s.setCode(req.code());
        s.setName(req.name());
        s.setDistrict(req.district());
        return mapper.toResponse(s);
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_SUBDIVISIONS, allEntries = true)
    public SubdivisionResponse setActive(Long id, boolean active) {
        Subdivision s = load(id);
        if (!active && s.isActive()) {
            // Guardrails on deactivation only — re-activation is always safe.
            if (distributionCenters.existsBySubdivisionIdAndActiveTrue(id)) {
                throw new BusinessException(ErrorCode.SUBDIVISION_HAS_ACTIVE_DCS);
            }
            if (staffLookup.hasActiveStaffInSubdivision(id)) {
                throw new BusinessException(ErrorCode.SUBDIVISION_HAS_ACTIVE_STAFF);
            }
        }
        s.setActive(active);
        return mapper.toResponse(s);
    }

    /**
     * Internal accessor for other modules that need to validate a subdivision exists & is active
     * (e.g. {@code DistributionCenterService} when creating a DC). Returns the entity directly
     * because it's a cross-service domain check, not an HTTP response.
     */
    @Transactional(readOnly = true)
    public Subdivision requireActive(Long id) {
        Subdivision s = load(id);
        if (!s.isActive()) {
            throw new BusinessException(ErrorCode.SUBDIVISION_INACTIVE);
        }
        return s;
    }

    private Subdivision load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBDIVISION_NOT_FOUND));
    }
}

