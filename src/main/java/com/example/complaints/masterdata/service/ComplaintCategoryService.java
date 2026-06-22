package com.example.complaints.masterdata.service;

import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.config.CaffeineCacheConfig;
import com.example.complaints.masterdata.dto.ComplaintCategoryRequest;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.mapper.ComplaintCategoryMapper;
import com.example.complaints.masterdata.model.ComplaintCategory;
import com.example.complaints.masterdata.repository.ComplaintCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ComplaintCategoryService {

    private final ComplaintCategoryRepository repo;
    private final ComplaintCategoryMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<ComplaintCategoryResponse> list(Pageable pageable) {
        return PageResponse.from(repo.findAll(pageable).map(mapper::toResponse));
    }

    /**
     * Active-only page used by the consumer-facing dropdown
     * ({@code GET /api/v1/consumer/masterdata/categories}). Staff reads keep using {@link #list}
     * so admins can see inactive rows.
     */
    @Transactional(readOnly = true)
    public PageResponse<ComplaintCategoryResponse> listActive(Pageable pageable) {
        return PageResponse.from(repo.findByActiveTrue(pageable).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CaffeineCacheConfig.CACHE_CATEGORIES, key = "#id")
    public ComplaintCategoryResponse get(Long id) {
        return mapper.toResponse(load(id));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_CATEGORIES, allEntries = true)
    public ComplaintCategoryResponse create(ComplaintCategoryRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CATEGORY_CODE_TAKEN);
        }
        ComplaintCategory c = ComplaintCategory.builder()
                .code(req.code())
                .name(req.name())
                .slaHours(req.slaHours())
                .active(true)
                .build();
        return mapper.toResponse(repo.save(c));
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_CATEGORIES, allEntries = true)
    public ComplaintCategoryResponse update(Long id, ComplaintCategoryRequest req) {
        ComplaintCategory c = load(id);
        if (!c.getCode().equals(req.code()) && repo.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.CATEGORY_CODE_TAKEN);
        }
        c.setCode(req.code());
        c.setName(req.name());
        c.setSlaHours(req.slaHours());
        return mapper.toResponse(c);
    }

    @Transactional
    @CacheEvict(value = CaffeineCacheConfig.CACHE_CATEGORIES, allEntries = true)
    public ComplaintCategoryResponse setActive(Long id, boolean active) {
        ComplaintCategory c = load(id);
        // TODO(sunil, phase-3): once the `complaint` module lands, block deactivation
        //   when open (non-terminal-status) complaints reference this category.
        //   Until then, category deactivation is effectively staff-discipline-only.
        c.setActive(active);
        return mapper.toResponse(c);
    }

    /** Cross-module accessor used by complaint creation (Phase 3). */
    @Transactional(readOnly = true)
    public ComplaintCategory requireActive(Long id) {
        ComplaintCategory c = load(id);
        if (!c.isActive()) {
            throw new BusinessException(ErrorCode.CATEGORY_INACTIVE);
        }
        return c;
    }

    private ComplaintCategory load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }
}

