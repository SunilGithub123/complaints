package com.example.complaints.complaint.service;

import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Cross-module read API for the {@code complaint} module. Exists so other modules (e.g.
 * {@code masterdata}) can ask scalar / aggregate questions about complaints without reaching
 * into {@code complaint.repository} directly — that hop is blocked by ArchUnit
 * ({@code modules_must_not_call_other_modules_repositories}).
 *
 * <p>Keep the surface intention-revealing and scalar-only. If a caller needs a {@code Complaint}
 * row, they should be inside the {@code complaint} module already.</p>
 */
@Service
@RequiredArgsConstructor
public class ComplaintQueryService {

    /**
     * Statuses considered "open" for the purpose of guarding master-data mutations. Any complaint
     * in one of these states still references the row's data on the technician's screen, in
     * scheduled SLA-breach jobs, etc. — deactivating the underlying master row would brick those
     * flows. Terminal statuses ({@code RESOLVED}, {@code CLOSED}, {@code CANCELLED},
     * {@code REJECTED}, {@code DUPLICATE}) are safe.
     */
    public static final Set<ComplaintStatus> OPEN_STATUSES = EnumSet.of(
            ComplaintStatus.SUBMITTED,
            ComplaintStatus.ASSIGNED,
            ComplaintStatus.IN_PROGRESS);

    private final ComplaintRepository complaints;

    @Transactional(readOnly = true)
    public boolean existsOpenForCategory(Long categoryId) {
        return complaints.existsByCategoryIdAndStatusIn(categoryId, OPEN_STATUSES);
    }
}

