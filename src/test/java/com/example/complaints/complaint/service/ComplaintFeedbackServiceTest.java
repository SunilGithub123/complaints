package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.FeedbackResponse;
import com.example.complaints.complaint.dto.SubmitFeedbackRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.event.FeedbackSubmittedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.model.Feedback;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.complaint.repository.FeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintFeedbackServiceTest {

    private ComplaintRepository complaints;
    private FeedbackRepository feedback;
    private ComplaintMapper mapper;
    private ApplicationEventPublisher events;
    private ComplaintFeedbackService service;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        feedback = mock(FeedbackRepository.class);
        mapper = mock(ComplaintMapper.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ComplaintFeedbackService(complaints, feedback, mapper, events);
    }

    @Test
    @DisplayName("submit: CLOSED + owner + no prior feedback → persists row with blank-normalised comment")
    void submit_happyPath() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));
        when(feedback.existsByComplaintId(7L)).thenReturn(false);
        when(feedback.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback in = inv.getArgument(0);
            in.setId(101L);
            return in;
        });
        when(mapper.toFeedbackResponse(any())).thenReturn(
                new FeedbackResponse(101L, 5, null, null));

        FeedbackResponse out = service.submit(caller, "MH20260600000007",
                new SubmitFeedbackRequest(5, "   "));   // blank → null

        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedback).save(captor.capture());
        Feedback saved = captor.getValue();
        assertThat(saved.getComplaintId()).isEqualTo(7L);
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getComment()).isNull();     // blank-normalised
        assertThat(out.id()).isEqualTo(101L);
        verify(events).publishEvent(any(FeedbackSubmittedEvent.class));
    }

    @Test
    @DisplayName("submit: complaint still IN_PROGRESS → 409 FEEDBACK_NOT_ALLOWED_YET")
    void submit_notClosed_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.IN_PROGRESS).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.submit(caller, "MH20260600000007",
                        new SubmitFeedbackRequest(4, "good work")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FEEDBACK_NOT_ALLOWED_YET));
    }

    @Test
    @DisplayName("submit: row already exists for this complaint → 409 FEEDBACK_ALREADY_SUBMITTED")
    void submit_duplicate_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));
        when(feedback.existsByComplaintId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(caller, "MH20260600000007",
                        new SubmitFeedbackRequest(3, null)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FEEDBACK_ALREADY_SUBMITTED));
    }

    @Test
    @DisplayName("submit: foreign ticket → 403 COMPLAINT_NOT_OWNED_BY_CONSUMER (state-leak guard)")
    void submit_foreignTicket_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(42L).status(ComplaintStatus.CLOSED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.submit(caller, "MH20260600000007",
                        new SubmitFeedbackRequest(5, null)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
    }

    @Test
    @DisplayName("getOwned: existing row → mapped FeedbackResponse")
    void getOwned_existing_returnsMapped() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        Feedback row = Feedback.builder().id(101L).complaintId(7L).rating(4).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));
        when(feedback.findByComplaintId(7L)).thenReturn(Optional.of(row));
        FeedbackResponse stub = new FeedbackResponse(101L, 4, null, null);
        when(mapper.toFeedbackResponse(row)).thenReturn(stub);

        assertThat(service.getOwned(caller, "MH20260600000007")).isSameAs(stub);
    }

    @Test
    @DisplayName("getOwned: no row yet → null (not an error)")
    void getOwned_missing_returnsNull() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(99L).status(ComplaintStatus.CLOSED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));
        when(feedback.findByComplaintId(7L)).thenReturn(Optional.empty());

        assertThat(service.getOwned(caller, "MH20260600000007")).isNull();
    }

    @Test
    @DisplayName("getOwned: foreign ticket → 403 COMPLAINT_NOT_OWNED_BY_CONSUMER")
    void getOwned_foreignTicket_rejected() {
        Complaint c = Complaint.builder().id(7L).ticketNo("MH20260600000007")
                .consumerMasterId(42L).status(ComplaintStatus.CLOSED).build();
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getOwned(caller, "MH20260600000007"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));
    }
}

