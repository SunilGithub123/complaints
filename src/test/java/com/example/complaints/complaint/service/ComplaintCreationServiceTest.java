package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.SubmitComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.consumer.dto.ConsumerView;
import com.example.complaints.consumer.service.ConsumerLookupService;
import com.example.complaints.masterdata.model.ComplaintCategory;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintCreationServiceTest {

    private ConsumerLookupService consumerLookup;
    private ComplaintCategoryService categoryService;
    private TicketNumberService ticketNumberService;
    private ComplaintRepository complaintRepo;
    private ComplaintHistoryRepository historyRepo;
    private ComplaintImageService imageService;
    private ComplaintMapper mapper;
    private ComplaintCreationService service;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void setUp() {
        consumerLookup = mock(ConsumerLookupService.class);
        categoryService = mock(ComplaintCategoryService.class);
        ticketNumberService = mock(TicketNumberService.class);
        complaintRepo = mock(ComplaintRepository.class);
        historyRepo = mock(ComplaintHistoryRepository.class);
        imageService = mock(ComplaintImageService.class);
        mapper = mock(ComplaintMapper.class);
        service = new ComplaintCreationService(consumerLookup, categoryService, ticketNumberService,
                complaintRepo, historyRepo, imageService, mapper);
    }

    @Test
    @DisplayName("submit persists complaint + initial history and returns mapped response")
    void submit_happyPath() {
        when(consumerLookup.requireActiveByConsumerId("MH00010001"))
                .thenReturn(new ConsumerView(99L, "MH00010001", "Asha", "+919999999999", 7L, true));
        when(categoryService.requireActive(3L))
                .thenReturn(ComplaintCategory.builder().id(3L).slaHours(24).active(true).build());
        when(ticketNumberService.nextTicketNumber()).thenReturn("MH20260600000123");
        when(complaintRepo.save(any(Complaint.class))).thenAnswer(inv -> {
            Complaint c = inv.getArgument(0);
            c.setId(555L);
            c.setCreatedAt(Instant.now());
            return c;
        });
        when(imageService.storeAll(anyLong(), any())).thenReturn(List.of());
        SubmitComplaintResponse stub = new SubmitComplaintResponse(
                555L, "MH20260600000123", ComplaintStatus.SUBMITTED, null, null, List.of());
        when(mapper.toSubmitResponse(any(), any())).thenReturn(stub);

        SubmitComplaintResponse result = service.submit(
                caller,
                new SubmitComplaintRequest("MH00010001", "+919999999999", 3L, "Power outage", "Plot 17"),
                List.of());

        assertThat(result).isSameAs(stub);
        ArgumentCaptor<Complaint> saved = ArgumentCaptor.forClass(Complaint.class);
        verify(complaintRepo).save(saved.capture());
        Complaint persisted = saved.getValue();
        assertThat(persisted.getTicketNo()).isEqualTo("MH20260600000123");
        assertThat(persisted.getStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(persisted.getDistributionCenterId()).isEqualTo(7L);
        assertThat(persisted.getContactMobile()).isEqualTo("+919999999999");
        assertThat(persisted.getSlaDeadline()).isAfter(Instant.now().plusSeconds(60L * 60L * 23L));

        ArgumentCaptor<ComplaintHistory> hist = ArgumentCaptor.forClass(ComplaintHistory.class);
        verify(historyRepo).save(hist.capture());
        assertThat(hist.getValue().getFromStatus()).isNull();
        assertThat(hist.getValue().getToStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        verify(imageService).storeAll(555L, List.of());
    }

    @Test
    @DisplayName("submit rejects when body's consumerId differs from the verified token's")
    void submit_consumerIdMismatch_rejected() {
        SubmitComplaintRequest req = new SubmitComplaintRequest(
                "MH00099999", "+919999999999", 3L, "Power outage", null);

        assertThatThrownBy(() -> service.submit(caller, req, List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
        verify(complaintRepo, never()).save(any());
        verify(ticketNumberService, never()).nextTicketNumber();
    }
}

