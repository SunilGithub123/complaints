package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.CancelComplaintRequest;
import com.example.complaints.complaint.event.ComplaintCancelledEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.ComplaintStatusTransition;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-driven cancellation (Stage 18). The consumer can withdraw a complaint they raised
 * <b>only</b> while it is still in {@link ComplaintStatus#SUBMITTED} — once an engineer has
 * assigned it to a technician, MSEB owns the workflow and the consumer can no longer cancel.
 *
 * <p>Ownership is enforced before any state check (and before any "wrong state" 409 is
 * raised) so that a non-owner cannot probe which tickets exist or in which state — same
 * privacy reasoning as {@link ComplaintReadService}.</p>
 *
 * <p>History row carries the consumer's user-id slot as {@code null} (consumers are not staff
 * and have no {@code user_account} row). The {@code note} embeds the consumer's external id
 * for audit reconstruction — staff opening the staff-side history endpoint will see e.g.
 * {@code "Cancelled by consumer MH00010001"}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintCancellationService {

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;

    private final ApplicationEventPublisher events;

    @Transactional
    public void cancel(VerifiedConsumer caller, String ticketNo, CancelComplaintRequest req) {
        Complaint c = complaints.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (!c.getConsumerMasterId().equals(caller.consumerMasterId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER);
        }
        // State machine validates the SUBMITTED → CANCELLED edge. We deliberately surface the
        // narrower COMPLAINT_NOT_IN_SUBMITTED_STATE here rather than the generic
        // COMPLAINT_INVALID_STATE_TRANSITION — the consumer UX wants to say "this can only be
        // cancelled before MSEB starts working on it", not generic 409 noise.
        if (c.getStatus() != ComplaintStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_IN_SUBMITTED_STATE);
        }
        ComplaintStatusTransition.requireValid(c.getStatus(), ComplaintStatus.CANCELLED);

        ComplaintStatus previous = c.getStatus();
        c.setStatus(ComplaintStatus.CANCELLED);
        c.setCancellationReason(req == null ? null : nullIfBlank(req.reason()));

        history.save(ComplaintHistory.builder()
                .complaintId(c.getId())
                .fromStatus(previous)
                .toStatus(ComplaintStatus.CANCELLED)
                // Consumers have no user_account row; null is the deliberate sentinel for
                // "non-staff actor". The note carries the external consumer id for audit.
                .changedByUserId(null)
                .note("Cancelled by consumer " + caller.consumerId())
                .build());

        events.publishEvent(new ComplaintCancelledEvent(
                c.getId(), c.getTicketNo(), c.getConsumerMasterId(),
                caller.consumerId(), c.getDistributionCenterId(), c.getCancellationReason()));

        log.info("Consumer {} cancelled complaint {}", caller.consumerId(), c.getId());
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

