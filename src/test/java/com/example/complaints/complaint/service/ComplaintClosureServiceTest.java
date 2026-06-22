package com.example.complaints.complaint.service;

import org.springframework.context.ApplicationEventPublisher;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.event.ComplaintClosedEvent;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintClosureServiceTest {

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ComplaintScopeGuard scope;
    private ApplicationEventPublisher events;
    private ComplaintClosureService service;

    private final AuthenticatedStaff engineer = new AuthenticatedStaff(
            1L, "ENG001", UserRole.ENGINEER, 100L, 10L, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        scope = mock(ComplaintScopeGuard.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ComplaintClosureService(complaints, history, scope, events);
        doNothing().when(scope).requireInScope(any(), any());
    }

    @Test
    @DisplayName("close: RESOLVED → CLOSED on-time, no breach reason needed")
    void close_happyPath() {
        Complaint c = baseResolved();
        c.setSlaDeadline(Instant.now().plus(1, ChronoUnit.HOURS));
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.close(engineer, 7L, new CloseComplaintRequest(null));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        assertThat(c.getClosedAt()).isNotNull();
        assertThat(c.isSlaBreached()).isFalse();
        verify(history).save(any());
        verify(events).publishEvent(any(ComplaintClosedEvent.class));
    }

    @Test
    @DisplayName("close: breached, no reason on-file, no reason in request → SLA_BREACH_REASON_REQUIRED")
    void close_breachedNoReason_rejected() {
        Complaint c = baseResolved();
        c.setSlaDeadline(Instant.now().minus(1, ChronoUnit.HOURS));
        c.setSlaBreached(true);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.close(engineer, 7L, new CloseComplaintRequest("")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SLA_BREACH_REASON_REQUIRED);
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("close: breach reason captured at resolve time is sufficient (no need to resend)")
    void close_breachedReasonOnFile_passes() {
        Complaint c = baseResolved();
        c.setSlaDeadline(Instant.now().minus(2, ChronoUnit.HOURS));
        c.setSlaBreached(true);
        c.setSlaBreachReason("Parts delay");
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.close(engineer, 7L, new CloseComplaintRequest(null));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        assertThat(c.getSlaBreachReason()).isEqualTo("Parts delay");
    }

    @Test
    @DisplayName("close: refuses an IN_PROGRESS complaint (state machine)")
    void close_fromInProgress_rejected() {
        Complaint c = baseResolved();
        c.setStatus(ComplaintStatus.IN_PROGRESS);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.close(engineer, 7L, new CloseComplaintRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
    }

    private Complaint baseResolved() {
        return Complaint.builder()
                .id(7L).ticketNo("MH20260600000007").consumerMasterId(99L)
                .contactMobile("+919999999999").categoryId(3L).description("x")
                .distributionCenterId(10L).status(ComplaintStatus.RESOLVED).slaBreached(false)
                .resolvedAt(Instant.now())
                .build();
    }
}

