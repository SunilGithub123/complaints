package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ComplaintStaffDetailResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Engineer / Admin read of a single complaint + its status-change history (Stage 13.5).
 *
 * <p>Mirrors {@link ComplaintReadService} (consumer side) but:</p>
 * <ul>
 *   <li>scope-checks via {@link ComplaintScopeGuard} (engineer DC / admin subdivision)
 *       rather than consumer ownership;</li>
 *   <li>returns a richer DTO exposing technician/engineer IDs, severity, breach state, and
 *       all the reason/notes fields a consumer never sees;</li>
 *   <li>history is its own endpoint so the detail payload stays bounded.</li>
 * </ul>
 *
 * <p>Paged list / search ships in Stage 16 via Specifications.</p>
 */
@Service
@RequiredArgsConstructor
public class ComplaintStaffReadService {

    private final ComplaintRepository complaints;
    private final ComplaintImageRepository images;
    private final ComplaintHistoryRepository history;
    private final ComplaintScopeGuard scope;
    private final ComplaintMapper mapper;

    @Transactional(readOnly = true)
    public ComplaintStaffDetailResponse getById(AuthenticatedStaff caller, Long id) {
        Complaint c = complaints.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        scope.requireInScope(caller, c);
        List<ComplaintImage> imgs = images.findByComplaintIdOrderByIdAsc(c.getId());
        return mapper.toStaffDetailResponse(c, imgs);
    }

    @Transactional(readOnly = true)
    public List<ComplaintHistoryEntryResponse> getHistory(AuthenticatedStaff caller, Long id) {
        Complaint c = complaints.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        scope.requireInScope(caller, c);
        return history.findByComplaintIdOrderByChangedAtAsc(c.getId()).stream()
                .map(mapper::toHistoryResponse)
                .toList();
    }
}

