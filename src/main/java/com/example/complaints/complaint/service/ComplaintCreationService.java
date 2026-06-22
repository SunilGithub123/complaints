package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.common.util.DateUtils;
import com.example.complaints.complaint.dto.SubmitComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.event.ComplaintSubmittedEvent;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.consumer.dto.ConsumerView;
import com.example.complaints.consumer.service.ConsumerLookupService;
import com.example.complaints.masterdata.model.ComplaintCategory;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Submits a new complaint on behalf of a {@link VerifiedConsumer}. End-to-end inside one
 * transaction:
 * <ol>
 *   <li>Cross-check the body's {@code consumerId} matches the verified JWT (anti-tamper).</li>
 *   <li>Re-load and active-check the consumer + category.</li>
 *   <li>Mint a ticket number ({@code TicketNumberService}, runs in its <b>own</b> tx).</li>
 *   <li>Persist {@code Complaint} (status=SUBMITTED, SLA = now+category.slaHours, DC derived from consumer-master).</li>
 *   <li>Persist {@code ComplaintHistory} ({@code null → SUBMITTED}).</li>
 *   <li>Validate + store images via {@link ComplaintImageService} (single external side-effect).</li>
 * </ol>
 *
 * <p>If image storage fails, the transaction rolls back and {@link ComplaintImageService} cleans
 * up any already-written keys — see its Javadoc.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintCreationService {

    private final ConsumerLookupService consumerLookup;
    private final ComplaintCategoryService categoryService;
    private final TicketNumberService ticketNumberService;
    private final ComplaintRepository complaintRepo;
    private final ComplaintHistoryRepository historyRepo;
    private final ComplaintImageService imageService;
    private final ComplaintMapper mapper;
    private final ApplicationEventPublisher events;

    @Transactional
    public SubmitComplaintResponse submit(VerifiedConsumer caller,
                                          SubmitComplaintRequest req,
                                          List<MultipartFile> images) {
        if (!caller.consumerId().equals(req.consumerId())) {
            throw new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER);
        }
        ConsumerView consumer = consumerLookup.requireActiveByConsumerId(req.consumerId());
        ComplaintCategory category = categoryService.requireActive(req.categoryId());

        String ticketNo = ticketNumberService.nextTicketNumber();
        Complaint saved = complaintRepo.save(buildComplaint(req, consumer, category, ticketNo));
        historyRepo.save(initialHistory(saved));

        List<ComplaintImage> savedImages = imageService.storeAll(saved.getId(), images);

        events.publishEvent(new ComplaintSubmittedEvent(
                saved.getId(), saved.getTicketNo(), saved.getConsumerMasterId(),
                saved.getContactMobile(), saved.getCategoryId(), saved.getDistributionCenterId()));

        log.info("Consumer {} submitted complaint {} (category {}, {} images)",
                caller.consumerId(), ticketNo, category.getId(), savedImages.size());
        return mapper.toSubmitResponse(saved, savedImages);
    }

    private Complaint buildComplaint(SubmitComplaintRequest req,
                                     ConsumerView consumer,
                                     ComplaintCategory category,
                                     String ticketNo) {
        OffsetDateTime slaDeadline = DateUtils.nowIst().plusHours(category.getSlaHours());
        return Complaint.builder()
                .ticketNo(ticketNo)
                .consumerMasterId(consumer.id())
                .contactMobile(req.mobile())
                .categoryId(category.getId())
                .description(req.description())
                .location(req.location())
                .distributionCenterId(consumer.distributionCenterId())
                .status(ComplaintStatus.SUBMITTED)
                .slaDeadline(slaDeadline.toInstant())
                .slaBreached(false)
                .build();
    }

    private static ComplaintHistory initialHistory(Complaint complaint) {
        return ComplaintHistory.builder()
                .complaintId(complaint.getId())
                .fromStatus(null)
                .toStatus(ComplaintStatus.SUBMITTED)
                .note("Submitted by consumer")
                .build();
    }
}

