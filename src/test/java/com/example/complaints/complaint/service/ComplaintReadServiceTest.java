package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplaintReadServiceTest {

    private ComplaintRepository complaintRepo;
    private ComplaintImageRepository imageRepo;
    private ComplaintMapper mapper;
    private ComplaintReadService service;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void setUp() {
        complaintRepo = mock(ComplaintRepository.class);
        imageRepo = mock(ComplaintImageRepository.class);
        mapper = mock(ComplaintMapper.class);
        service = new ComplaintReadService(complaintRepo, imageRepo, mapper);
    }

    @Test
    @DisplayName("getOwnedByTicketNo returns the mapped detail when the consumer owns the ticket")
    void getOwnedByTicketNo_ownedTicket_returnsDetail() {
        Complaint c = Complaint.builder().id(1L).ticketNo("MH20260600000123")
                .consumerMasterId(99L).status(ComplaintStatus.SUBMITTED).build();
        when(complaintRepo.findByTicketNo("MH20260600000123")).thenReturn(Optional.of(c));
        when(imageRepo.findByComplaintIdOrderByIdAsc(1L)).thenReturn(List.of());
        ComplaintDetailResponse stub = new ComplaintDetailResponse(
                1L, "MH20260600000123", "MH00010001", "+919999999999",
                3L, "desc", null, ComplaintStatus.SUBMITTED, null, null, List.of());
        when(mapper.toDetailResponse(eq(c), eq("MH00010001"), any())).thenReturn(stub);

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
}

