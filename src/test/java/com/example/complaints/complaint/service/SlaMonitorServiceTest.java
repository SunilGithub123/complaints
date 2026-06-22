package com.example.complaints.complaint.service;

import com.example.complaints.complaint.event.SlaBreachedEvent;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlaMonitorServiceTest {

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ApplicationEventPublisher events;
    private SlaMonitorService service;

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new SlaMonitorService(complaints, history, events);
    }

    @Test
    @DisplayName("markBreached: flips slaBreached on overdue rows + writes system-actor history")
    void markBreached_happyPath() {
        Complaint c1 = overdue(7L, ComplaintStatus.ASSIGNED);
        Complaint c2 = overdue(8L, ComplaintStatus.IN_PROGRESS);
        when(complaints.findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore(
                eq(ComplaintQueryService.OPEN_STATUSES), any(Instant.class)))
                .thenReturn(List.of(c1, c2));

        service.markBreached();

        assertThat(c1.isSlaBreached()).isTrue();
        assertThat(c2.isSlaBreached()).isTrue();
        ArgumentCaptor<ComplaintHistory> rows = ArgumentCaptor.forClass(ComplaintHistory.class);
        verify(history, times(2)).save(rows.capture());
        // Both rows are system-driven (null actor) and don't move status.
        rows.getAllValues().forEach(h -> {
            assertThat(h.getChangedByUserId()).isNull();
            assertThat(h.getFromStatus()).isEqualTo(h.getToStatus());
            assertThat(h.getNote()).contains("SLA breached");
        });
        verify(events, times(2)).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    @DisplayName("markBreached: empty sweep is a no-op (no writes, no exception)")
    void markBreached_emptySweep_noOp() {
        when(complaints.findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore(any(), any()))
                .thenReturn(List.of());

        service.markBreached();

        verify(history, never()).save(any());
    }

    private Complaint overdue(Long id, ComplaintStatus status) {
        return Complaint.builder()
                .id(id).ticketNo("MH202606" + id).consumerMasterId(99L)
                .contactMobile("+919999999999").categoryId(3L).description("x")
                .distributionCenterId(10L).status(status).slaBreached(false)
                .slaDeadline(Instant.now().minus(2, ChronoUnit.HOURS))
                .build();
    }
}

