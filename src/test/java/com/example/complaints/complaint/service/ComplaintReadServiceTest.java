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
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.complaint.repository.FeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplaintReadServiceTest {

    private ComplaintRepository complaintRepo;
    private ComplaintImageRepository imageRepo;
    private ComplaintHistoryRepository historyRepo;
    private FeedbackRepository feedbackRepo;
    private ComplaintMapper mapper;
    private ComplaintReadService service;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void setUp() {
        complaintRepo = mock(ComplaintRepository.class);
        imageRepo = mock(ComplaintImageRepository.class);
        historyRepo = mock(ComplaintHistoryRepository.class);
        feedbackRepo = mock(FeedbackRepository.class);
        mapper = mock(ComplaintMapper.class);
        service = new ComplaintReadService(complaintRepo, imageRepo, historyRepo, feedbackRepo, mapper);
    }

    @Test
    @DisplayName("getOwnedByTicketNo returns the mapped detail when the consumer owns the ticket")
    void getOwnedByTicketNo_ownedTicket_returnsDetail() {
        Complaint c = Complaint.builder().id(1L).ticketNo("MH20260600000123")
                .consumerMasterId(99L).status(ComplaintStatus.SUBMITTED).build();
        when(complaintRepo.findByTicketNo("MH20260600000123")).thenReturn(Optional.of(c));
        when(imageRepo.findByComplaintIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(feedbackRepo.existsByComplaintId(1L)).thenReturn(false);
        ComplaintDetailResponse stub = new ComplaintDetailResponse(
                1L, "MH20260600000123", "MH00010001", "+919999999999",
                3L, null, "desc", null, ComplaintStatus.SUBMITTED, false,
                null, null, null, null, false, List.of());
        when(mapper.toDetailResponse(eq(c), eq("MH00010001"), any(), eq(false))).thenReturn(stub);

        assertThat(service.getOwnedByTicketNo(caller, "MH20260600000123")).isSameAs(stub);
    }

    @Test
    @DisplayName("getOwnedByTicketNo rejects when the ticket exists but belongs to another consumer")
    void getOwnedByTicketNo_foreignTicket_rejected() {
        Complaint c = Complaint.builder().id(1L).ticketNo("MH20260600000999")
                .consumerMasterId(42L).build();   // different owner
        when(complaintRepo.findByTicketNo("MH20260600000999")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOwnedByTicketNo(caller, "MH20260600000999"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
    }

    @Test
    @DisplayName("getOwnedByTicketNo returns 404 when the ticket does not exist at all")
    void getOwnedByTicketNo_missingTicket_returnsNotFound() {
        when(complaintRepo.findByTicketNo("MH20260600009999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedByTicketNo(caller, "MH20260600009999"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_FOUND));
    }

    @Test
    @DisplayName("listOwned scopes to the caller's consumerMasterId and maps each row")
    @SuppressWarnings("unchecked")
    void listOwned_happyPath() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.RESOLVED).build();
        Page<Complaint> page = new PageImpl<>(List.of(c));
        when(complaintRepo.findAll(any(Specification.class), eq(PageRequest.of(0, 20))))
                .thenReturn(page);
        ConsumerComplaintListItemResponse row = new ConsumerComplaintListItemResponse(
                7L, "MH20260600000007", 3L, null, ComplaintStatus.RESOLVED, false,
                null, null, null, null, false);
        when(mapper.toConsumerListItem(eq(c), eq(false))).thenReturn(row);
        when(feedbackRepo.findComplaintIdsWithFeedback(any())).thenReturn(List.of());

        PageResponse<ConsumerComplaintListItemResponse> result =
                service.listOwned(caller, null, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(row);
    }

    @Test
    @DisplayName("listOwned marks feedbackSubmitted=true on rows the batch lookup found")
    @SuppressWarnings("unchecked")
    void listOwned_marksFeedbackSubmitted() {
        Complaint rated = Complaint.builder().id(7L).ticketNo("T7")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        Complaint notRated = Complaint.builder().id(8L).ticketNo("T8")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        when(complaintRepo.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(rated, notRated)));
        when(feedbackRepo.findComplaintIdsWithFeedback(any())).thenReturn(List.of(7L));
        ConsumerComplaintListItemResponse ratedRow = new ConsumerComplaintListItemResponse(
                7L, "T7", 3L, null, ComplaintStatus.CLOSED, false, null, null, null, null, true);
        ConsumerComplaintListItemResponse notRatedRow = new ConsumerComplaintListItemResponse(
                8L, "T8", 3L, null, ComplaintStatus.CLOSED, false, null, null, null, null, false);
        when(mapper.toConsumerListItem(eq(rated), eq(true))).thenReturn(ratedRow);
        when(mapper.toConsumerListItem(eq(notRated), eq(false))).thenReturn(notRatedRow);

        PageResponse<ConsumerComplaintListItemResponse> result =
                service.listOwned(caller, null, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(ratedRow, notRatedRow);
    }

    @Test
    @DisplayName("getOwnedHistory returns consumer-safe rows (no changedByUserId leak path)")
    void getOwnedHistory_happyPath() {
        Complaint c = Complaint.builder().id(1L).ticketNo("MH20260600000123")
                .consumerMasterId(99L).build();
        when(complaintRepo.findByTicketNo("MH20260600000123")).thenReturn(Optional.of(c));
        ComplaintHistory h = ComplaintHistory.builder().id(11L)
                .fromStatus(ComplaintStatus.SUBMITTED).toStatus(ComplaintStatus.ASSIGNED)
                .changedByUserId(42L).note("Assigned").build();
        when(historyRepo.findByComplaintIdOrderByChangedAtAsc(1L)).thenReturn(List.of(h));
        ConsumerComplaintHistoryEntryResponse mapped = new ConsumerComplaintHistoryEntryResponse(
                11L, ComplaintStatus.SUBMITTED, ComplaintStatus.ASSIGNED, "Assigned", null);
        when(mapper.toConsumerHistoryResponse(h)).thenReturn(mapped);

        assertThat(service.getOwnedHistory(caller, "MH20260600000123")).containsExactly(mapped);
    }

    @Test
    @DisplayName("getOwnedHistory rejects when the ticket belongs to another consumer")
    void getOwnedHistory_foreignTicket_rejected() {
        Complaint c = Complaint.builder().id(1L).ticketNo("MH20260600000999")
                .consumerMasterId(42L).build();
        when(complaintRepo.findByTicketNo("MH20260600000999")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOwnedHistory(caller, "MH20260600000999"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
    }
}

