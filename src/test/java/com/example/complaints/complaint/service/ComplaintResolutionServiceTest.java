package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintResolutionServiceTest {

    private static final Long TECH_ID = 2L;

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ComplaintImageService imageService;
    private ComplaintMapper mapper;
    private ComplaintResolutionService service;

    private final AuthenticatedStaff technician = new AuthenticatedStaff(
            TECH_ID, "TECH001", UserRole.TECHNICIAN, 100L, 10L, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        imageService = mock(ComplaintImageService.class);
        mapper = mock(ComplaintMapper.class);
        service = new ComplaintResolutionService(complaints, history, imageService, mapper);
    }

    @Test
    @DisplayName("start: ASSIGNED → IN_PROGRESS for the assigned technician")
    void start_happyPath() {
        Complaint c = base(ComplaintStatus.ASSIGNED, TECH_ID);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.start(technician, 7L);

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        verify(history).save(any());
    }

    @Test
    @DisplayName("start: blocked when the technician is not the assigned owner")
    void start_notAssigned_rejected() {
        Complaint c = base(ComplaintStatus.ASSIGNED, 999L); // someone else
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.start(technician, 7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN);
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("resolve: on-time → RESOLVED, breach flag NOT set")
    void resolve_onTime_happyPath() {
        Complaint c = base(ComplaintStatus.IN_PROGRESS, TECH_ID);
        c.setSlaDeadline(Instant.now().plus(1, ChronoUnit.HOURS));
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.resolve(technician, 7L, new ResolveComplaintRequest("Fixed loose wire", null));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(c.getResolutionNotes()).isEqualTo("Fixed loose wire");
        assertThat(c.isSlaBreached()).isFalse();
        assertThat(c.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolve: past SLA without a breach reason → SLA_BREACH_REASON_REQUIRED")
    void resolve_breachedNoReason_rejected() {
        Complaint c = base(ComplaintStatus.IN_PROGRESS, TECH_ID);
        c.setSlaDeadline(Instant.now().minus(1, ChronoUnit.HOURS));
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.resolve(technician, 7L,
                new ResolveComplaintRequest("Fixed", "")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SLA_BREACH_REASON_REQUIRED);
    }

    @Test
    @DisplayName("resolve: past SLA WITH a reason flips slaBreached and stores reason")
    void resolve_breachedWithReason_flagsBreach() {
        Complaint c = base(ComplaintStatus.IN_PROGRESS, TECH_ID);
        c.setSlaDeadline(Instant.now().minus(1, ChronoUnit.HOURS));
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        service.resolve(technician, 7L,
                new ResolveComplaintRequest("Took longer than expected", "Parts delay"));

        assertThat(c.isSlaBreached()).isTrue();
        assertThat(c.getSlaBreachReason()).isEqualTo("Parts delay");
    }

    @Test
    @DisplayName("addResolutionImages: refused on a SUBMITTED complaint (not yet started)")
    void addResolutionImages_wrongStatus_rejected() {
        Complaint c = base(ComplaintStatus.ASSIGNED, TECH_ID);
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.addResolutionImages(technician, 7L, java.util.List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
    }

    private Complaint base(ComplaintStatus status, Long technicianId) {
        return Complaint.builder()
                .id(7L).ticketNo("MH20260600000007").consumerMasterId(99L)
                .contactMobile("+919999999999").categoryId(3L).description("x")
                .distributionCenterId(10L)
                .assignedTechnicianId(technicianId)
                .status(status).slaBreached(false)
                .slaDeadline(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }
}

