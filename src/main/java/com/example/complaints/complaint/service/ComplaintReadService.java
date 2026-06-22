package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Minimal consumer-side read of a single complaint for the post-submit confirmation screen
 * (and a refresh-safe re-fetch from the same screen). Scope strictly limited per the Stage 10b
 * decision: consumer-tracking, history, and feedback land in Phase 5.
 *
 * <p>Ownership check: the verified consumer's external ID must equal the consumer-master row's
 * external ID on the complaint, otherwise {@link ErrorCode#COMPLAINT_NOT_OWNED_BY_CONSUMER}.
 * Returning {@code NOT_FOUND} when the row exists but belongs to a different consumer would
 * leak ticket-number existence (BRD §6 privacy note).</p>
 */
@Service
@RequiredArgsConstructor
public class ComplaintReadService {

    private final ComplaintRepository complaintRepo;
    private final ComplaintImageRepository imageRepo;
    private final ComplaintMapper mapper;

    @Transactional(readOnly = true)
    public ComplaintDetailResponse getOwnedByTicketNo(VerifiedConsumer caller, String ticketNo) {
        Complaint c = complaintRepo.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (!c.getConsumerMasterId().equals(caller.consumerMasterId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER);
        }
        List<ComplaintImage> images = imageRepo.findByComplaintIdOrderByIdAsc(c.getId());
        return mapper.toDetailResponse(c, caller.consumerId(), images);
    }
}

