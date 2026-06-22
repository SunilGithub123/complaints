package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.dto.ConsumerComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ConsumerComplaintListItemResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.complaint.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.example.complaints.complaint.service.ComplaintSpecifications.consumerMasterIdEq;
import static com.example.complaints.complaint.service.ComplaintSpecifications.statusEq;

/**
 * Consumer-side reads (Stage 10b + Stage 17):
 * <ul>
 *   <li>Single complaint detail by ticket number — owner-checked confirmation / refresh view.</li>
 *   <li>Paged tracking list — every complaint the verified consumer has ever raised, scope-pinned
 *       to {@code consumerMasterId}, optional {@code status} filter.</li>
 *   <li>History timeline — consumer-safe (no {@code changedByUserId}).</li>
 * </ul>
 *
 * <p>Ownership check on every method: the verified consumer's external ID must equal the
 * consumer-master row's external ID on the complaint, otherwise
 * {@link ErrorCode#COMPLAINT_NOT_OWNED_BY_CONSUMER}. Returning {@code NOT_FOUND} when the row
 * exists but belongs to a different consumer would leak ticket-number existence (BRD §6 privacy
 * note).</p>
 */
@Service
@RequiredArgsConstructor
public class ComplaintReadService {

    private final ComplaintRepository complaintRepo;
    private final ComplaintImageRepository imageRepo;
    private final ComplaintHistoryRepository historyRepo;
    private final FeedbackRepository feedbackRepo;
    private final ComplaintMapper mapper;

    @Transactional(readOnly = true)
    public ComplaintDetailResponse getOwnedByTicketNo(VerifiedConsumer caller, String ticketNo) {
        Complaint c = requireOwnedByTicketNo(caller, ticketNo);
        List<ComplaintImage> images = imageRepo.findByComplaintIdOrderByIdAsc(c.getId());
        boolean feedbackSubmitted = feedbackRepo.existsByComplaintId(c.getId());
        return mapper.toDetailResponse(c, caller.consumerId(), images, feedbackSubmitted);
    }

    /**
     * Paged tracking list for the verified consumer. Server pins {@code consumerMasterId == me};
     * the only user-supplied filter is {@code status}. Default sort is {@code createdAt,desc}
     * (handled by {@code @PageableDefault} on the controller).
     *
     * <p>Stage 20.2: each row carries {@code feedbackSubmitted}. We resolve that via a single
     * batch query against {@code FeedbackRepository.findComplaintIdsWithFeedback(ids)} rather
     * than N per-row probes — page size is capped at 100, so the IN list stays bounded.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<ConsumerComplaintListItemResponse> listOwned(
            VerifiedConsumer caller, ComplaintStatus status, Pageable pageable) {

        Specification<Complaint> spec = combine(
                consumerMasterIdEq(caller.consumerMasterId()),
                statusEq(status)
        );
        var page = complaintRepo.findAll(spec, pageable);
        Set<Long> withFeedback = page.isEmpty()
                ? Set.of()
                : new HashSet<>(feedbackRepo.findComplaintIdsWithFeedback(
                        page.stream().map(Complaint::getId).toList()));
        return PageResponse.from(page.map(c ->
                mapper.toConsumerListItem(c, withFeedback.contains(c.getId()))));
    }

    /**
     * Status-change audit trail for the verified consumer. Owner-checked; the returned shape
     * omits {@code changedByUserId} to avoid leaking staff IDs to the consumer.
     */
    @Transactional(readOnly = true)
    public List<ConsumerComplaintHistoryEntryResponse> getOwnedHistory(
            VerifiedConsumer caller, String ticketNo) {

        Complaint c = requireOwnedByTicketNo(caller, ticketNo);
        return historyRepo.findByComplaintIdOrderByChangedAtAsc(c.getId()).stream()
                .map(mapper::toConsumerHistoryResponse)
                .toList();
    }

    private Complaint requireOwnedByTicketNo(VerifiedConsumer caller, String ticketNo) {
        Complaint c = complaintRepo.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPLAINT_NOT_FOUND));
        if (!c.getConsumerMasterId().equals(caller.consumerMasterId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER);
        }
        return c;
    }

    /**
     * Null-tolerant AND combinator. Mirrors {@code ComplaintSearchService.combine(...)} — see
     * the comment there for the rationale (Spring Data 4 {@code Specification.allOf(...)} NPEs
     * on null arms).
     */
    @SafeVarargs
    private static Specification<Complaint> combine(Specification<Complaint>... specs) {
        return Arrays.stream(specs)
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElseGet(() -> (root, query, cb) -> cb.conjunction());
    }
}
