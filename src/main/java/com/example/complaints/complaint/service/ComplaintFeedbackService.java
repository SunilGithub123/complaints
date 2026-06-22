package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.FeedbackResponse;
import com.example.complaints.complaint.dto.SubmitFeedbackRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.Feedback;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.complaint.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-driven feedback (Stage 19). One row per closed complaint — enforced both at the
 * service layer ({@link FeedbackRepository#existsByComplaintId}) for a friendly
 * {@link ErrorCode#FEEDBACK_ALREADY_SUBMITTED} error code, and at the DB layer
 * ({@code UNIQUE(complaint_id)} on the {@code feedback} table) as the real safety net.
 *
 * <p>Order of checks (each gates the next):</p>
 * <ol>
 *   <li><b>Ticket exists</b> — {@code COMPLAINT_NOT_FOUND} otherwise.</li>
 *   <li><b>Owned by caller</b> — {@code COMPLAINT_NOT_OWNED_BY_CONSUMER} otherwise.
 *       Owner check happens <b>before</b> state / dup checks so a non-owner cannot probe.</li>
 *   <li><b>Status = CLOSED</b> — {@code FEEDBACK_NOT_ALLOWED_YET} otherwise. Feedback on
 *       CANCELLED / REJECTED / DUPLICATE complaints is meaningless; only CLOSED rows have
 *       a resolution to rate.</li>
 *   <li><b>Not yet submitted</b> — {@code FEEDBACK_ALREADY_SUBMITTED} otherwise.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintFeedbackService {

    private final ComplaintRepository complaints;
    private final FeedbackRepository feedback;
    private final ComplaintMapper mapper;

    @Transactional
    public FeedbackResponse submit(VerifiedConsumer caller, String ticketNo, SubmitFeedbackRequest req) {
        Complaint c = complaints.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (!c.getConsumerMasterId().equals(caller.consumerMasterId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER);
        }
        if (c.getStatus() != ComplaintStatus.CLOSED) {
            throw new BusinessException(ErrorCode.FEEDBACK_NOT_ALLOWED_YET);
        }
        if (feedback.existsByComplaintId(c.getId())) {
            throw new BusinessException(ErrorCode.FEEDBACK_ALREADY_SUBMITTED);
        }

        Feedback saved = feedback.save(Feedback.builder()
                .complaintId(c.getId())
                .rating(req.rating())
                .comment(nullIfBlank(req.comment()))
                .build());

        log.info("Consumer {} left feedback {}★ on complaint {}",
                caller.consumerId(), saved.getRating(), c.getId());
        return mapper.toFeedbackResponse(saved);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

