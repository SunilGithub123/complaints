package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ComplaintSearchRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.masterdata.service.DistributionCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.example.complaints.complaint.service.ComplaintSpecifications.categoryEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.createdFrom;
import static com.example.complaints.complaint.service.ComplaintSpecifications.createdTo;
import static com.example.complaints.complaint.service.ComplaintSpecifications.dcEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.dcIn;
import static com.example.complaints.complaint.service.ComplaintSpecifications.severityEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.slaBreachedEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.statusEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.technicianEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.textSearch;

/**
 * Paged + filtered complaint search (Stage 16). Two surfaces:
 *
 * <ul>
 *   <li>{@link #listForStaff} — engineers / admins; scope-locked to caller's DC or subdivision.</li>
 *   <li>{@link #listForTechnician} — technicians; scope-locked to
 *       {@code assigned_technician_id == me}.</li>
 * </ul>
 *
 * <p>Scope rules are composed into the {@link Specification} <b>before</b> any user-supplied
 * filter, so a filter cannot widen the caller's reach. Sort + page are driven by the standard
 * Spring {@link Pageable} (default {@code createdAt,desc} per {@code PageResponse.defaultSort}).</p>
 */
@Service
@RequiredArgsConstructor
public class ComplaintSearchService {

    private final ComplaintRepository complaints;
    private final ComplaintMapper mapper;
    private final DistributionCenterService dcs;

    @Transactional(readOnly = true)
    public PageResponse<ComplaintListItemResponse> listForStaff(
            AuthenticatedStaff caller, ComplaintSearchRequest req, Pageable pageable) {

        Specification<Complaint> spec = combine(
                staffScope(caller, req.distributionCenterId()),
                statusEq(req.status()),
                severityEq(req.severity()),
                categoryEq(req.categoryId()),
                technicianEq(req.assignedTechnicianId()),
                slaBreachedEq(req.slaBreached()),
                createdFrom(req.dateFrom()),
                createdTo(req.dateTo()),
                textSearch(req.q())
        );
        return PageResponse.from(complaints.findAll(spec, pageable).map(mapper::toListItem));
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintListItemResponse> listForTechnician(
            AuthenticatedStaff caller, ComplaintSearchRequest req, Pageable pageable) {

        if (caller.role() != UserRole.TECHNICIAN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        // Technician scope is rigid: their own assigned complaints only. distributionCenterId,
        // categoryId, assignedTechnicianId from req are ignored — a technician can't pivot to
        // someone else's queue. status / severity / slaBreached / dateRange / q still apply.
        Specification<Complaint> spec = combine(
                technicianEq(caller.userId()),
                statusEq(req.status()),
                severityEq(req.severity()),
                slaBreachedEq(req.slaBreached()),
                createdFrom(req.dateFrom()),
                createdTo(req.dateTo()),
                textSearch(req.q())
        );
        return PageResponse.from(complaints.findAll(spec, pageable).map(mapper::toListItem));
    }

    /**
     * Null-tolerant {@code AND} combinator. Spring Data 4's {@code Specification.allOf(...)}
     * NPEs on null arms, so this helper filters them out — keeps the call sites readable
     * (each predicate factory returns {@code null} when its filter is absent).
     */
    @SafeVarargs
    private static Specification<Complaint> combine(Specification<Complaint>... specs) {
        return Arrays.stream(specs)
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElseGet(() -> (root, query, cb) -> cb.conjunction());
    }

    /**
     * Engineer: results must be in caller's DC. A non-null {@code requestedDcId} that does not
     * match is rejected with 403 (don't silently rewrite — same policy as the directory search
     * in Stage 14.6).
     *
     * <p>Admin: results must be within caller's subdivision. A non-null {@code requestedDcId}
     * narrows to that DC <b>only if</b> it belongs to the admin's subdivision; otherwise it's
     * a 403. Without {@code requestedDcId}, the predicate is an {@code IN (...)} of every DC
     * in the subdivision.</p>
     */
    private Specification<Complaint> staffScope(AuthenticatedStaff caller, Long requestedDcId) {
        if (caller.role() == UserRole.ENGINEER) {
            if (requestedDcId != null && !requestedDcId.equals(caller.distributionCenterId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return dcEq(caller.distributionCenterId());
        }
        if (caller.role() == UserRole.ADMIN) {
            List<Long> dcsInSubdivision = dcs.findDcIdsInSubdivision(caller.subdivisionId());
            if (requestedDcId != null) {
                if (!dcsInSubdivision.contains(requestedDcId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                return dcEq(requestedDcId);
            }
            return dcIn(dcsInSubdivision);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}




