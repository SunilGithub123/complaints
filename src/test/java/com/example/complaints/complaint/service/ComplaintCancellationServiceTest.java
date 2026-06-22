package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.CancelComplaintRequest;
import com.example.complaints.complaint.event.ComplaintCancelledEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintCancellationServiceTest {

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ApplicationEventPublisher events;
    private ComplaintCancellationService service;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ComplaintCancellationService(complaints, history, events);
    }

    @Test
    @DisplayName("cancel: SUBMITTED + owner + reason → status=CANCELLED, reason persisted, history row written")
    void cancel_happyPath() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.SUBMITTED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        service.cancel(caller, "MH20260600000007", new CancelComplaintRequest("Resolved on its own"));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.CANCELLED);
        assertThat(c.getCancellationReason()).isEqualTo("Resolved on its own");
        ArgumentCaptor<ComplaintHistory> captor = ArgumentCaptor.forClass(ComplaintHistory.class);
        verify(history).save(captor.capture());
        ComplaintHistory saved = captor.getValue();
        assertThat(saved.getFromStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(saved.getToStatus()).isEqualTo(ComplaintStatus.CANCELLED);
        assertThat(saved.getChangedByUserId()).isNull();    // consumer actor → null
        assertThat(saved.getNote()).contains("MH00010001"); // external id captured in note
        verify(events).publishEvent(any(ComplaintCancelledEvent.class));
    }

    @Test
    @DisplayName("cancel: non-SUBMITTED state → 409 COMPLAINT_NOT_IN_SUBMITTED_STATE")
    void cancel_assigned_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.ASSIGNED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.cancel(caller, "MH20260600000007",
                        new CancelComplaintRequest(null)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_IN_SUBMITTED_STATE));
    }

    @Test
    @DisplayName("cancel: ticket owned by another consumer → 403 COMPLAINT_NOT_OWNED_BY_CONSUMER (no state leak)")
    void cancel_foreignTicket_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(42L).status(ComplaintStatus.SUBMITTED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.cancel(caller, "MH20260600000007",
                        new CancelComplaintRequest("nope")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
    }
}

