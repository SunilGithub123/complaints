package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.MarkDuplicateRequest;
import com.example.complaints.complaint.dto.RejectComplaintRequest;
import com.example.complaints.complaint.dto.UpdateSeverityRequest;
import com.example.complaints.complaint.event.ComplaintRejectedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintSeverity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintTriageServiceTest {

    private static final Long DC = 10L;
    private static final Long SUBDIV = 100L;

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ComplaintScopeGuard scope;
    private ApplicationEventPublisher events;
    private ComplaintTriageService service;

    private final AuthenticatedStaff engineer = new AuthenticatedStaff(
            1L, "ENG001", UserRole.ENGINEER, SUBDIV, DC, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        scope = mock(ComplaintScopeGuard.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ComplaintTriageService(complaints, history, scope, events);
        doNothing().when(scope).requireInScope(any(), any());
    }

    @Test
    @DisplayName("updateSeverity: mutates severity, status unchanged, writes audit row")
    void updateSeverity_happyPath() {
        Complaint c = baseAt(ComplaintStatus.ASSIGNED);
        c.setSeverity(ComplaintSeverity.LOW);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.updateSeverity(engineer, 7L, new UpdateSeverityRequest(ComplaintSeverity.HIGH));

        assertThat(c.getSeverity()).isEqualTo(ComplaintSeverity.HIGH);
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        verify(history).save(any());
    }

    @Test
    @DisplayName("updateSeverity: rejected on terminal status (CLOSED)")
    void updateSeverity_terminalStatus_rejected() {
        Complaint c = baseAt(ComplaintStatus.CLOSED);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() ->
                service.updateSeverity(engineer, 7L, new UpdateSeverityRequest(ComplaintSeverity.HIGH)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("reject: SUBMITTED → REJECTED with reason persisted")
    void reject_happyPath() {
        Complaint c = baseAt(ComplaintStatus.SUBMITTED);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.reject(engineer, 7L, new RejectComplaintRequest("Out of jurisdiction"));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.REJECTED);
        assertThat(c.getRejectionReason()).isEqualTo("Out of jurisdiction");
        ArgumentCaptor<ComplaintHistory> h = ArgumentCaptor.forClass(ComplaintHistory.class);
        verify(history).save(h.capture());
        assertThat(h.getValue().getToStatus()).isEqualTo(ComplaintStatus.REJECTED);
        verify(events).publishEvent(any(ComplaintRejectedEvent.class));
    }

    @Test
    @DisplayName("reject: fails on ASSIGNED (state machine refuses)")
    void reject_fromAssigned_rejected() {
        Complaint c = baseAt(ComplaintStatus.ASSIGNED);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() ->
                service.reject(engineer, 7L, new RejectComplaintRequest("nope")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("markDuplicate: SUBMITTED → DUPLICATE, parent FK set")
    void markDuplicate_happyPath() {
        Complaint c = baseAt(ComplaintStatus.SUBMITTED);
        Complaint parent = baseAt(ComplaintStatus.ASSIGNED);
        parent.setId(99L);
        parent.setTicketNo("MH20260600000099");
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(complaints.findByTicketNo("MH20260600000099")).thenReturn(Optional.of(parent));

        service.markDuplicate(engineer, 7L,
                new MarkDuplicateRequest("MH20260600000099", "same outage"));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.DUPLICATE);
        assertThat(c.getParentComplaintId()).isEqualTo(99L);
        verify(history).save(any());
    }

    @Test
    @DisplayName("markDuplicate: refuses self-reference")
    void markDuplicate_selfReference_rejected() {
        Complaint c = baseAt(ComplaintStatus.SUBMITTED);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(complaints.findByTicketNo("MH20260600000007")).thenReturn(Optional.of(c));

        assertThatThrownBy(() ->
                service.markDuplicate(engineer, 7L,
                        new MarkDuplicateRequest("MH20260600000007", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_OF_SELF);
        verify(history, never()).save(any());
    }

    private Complaint baseAt(ComplaintStatus s) {
        return Complaint.builder()
                .id(7L)
                .ticketNo("MH20260600000007")
                .consumerMasterId(99L)
                .contactMobile("+919999999999")
                .categoryId(3L)
                .description("x")
                .distributionCenterId(DC)
                .status(s)
                .slaBreached(false)
                .build();
    }
}

