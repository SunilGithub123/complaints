package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.auth.service.StaffScopeView;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.ReassignComplaintRequest;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintAssignmentServiceTest {

    private static final Long DC = 10L;
    private static final Long OTHER_DC = 20L;
    private static final Long SUBDIV = 100L;
    private static final Long ENGINEER_ID = 1L;
    private static final Long TECH_ID = 2L;

    private ComplaintRepository complaints;
    private ComplaintHistoryRepository history;
    private ComplaintScopeGuard scope;
    private StaffLookupService staff;
    private ComplaintAssignmentService service;

    private final AuthenticatedStaff engineer = new AuthenticatedStaff(
            ENGINEER_ID, "ENG001", UserRole.ENGINEER, SUBDIV, DC, false);
    private final AuthenticatedStaff admin = new AuthenticatedStaff(
            5L, "ADM001", UserRole.ADMIN, SUBDIV, null, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        scope = mock(ComplaintScopeGuard.class);
        staff = mock(StaffLookupService.class);
        service = new ComplaintAssignmentService(complaints, history, scope, staff);
        doNothing().when(scope).requireInScope(any(), any());
    }

    @Test
    @DisplayName("assign: engineer assigns SUBMITTED complaint, status→ASSIGNED, engineer FK = caller")
    void assign_happyPath_engineer() {
        Complaint c = baseSubmitted();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(staff.getActiveTechnician(TECH_ID))
                .thenReturn(new StaffScopeView(TECH_ID, UserRole.TECHNICIAN, SUBDIV, DC, true));

        service.assign(engineer, 7L, new AssignComplaintRequest(TECH_ID, ComplaintSeverity.HIGH));

        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        assertThat(c.getAssignedTechnicianId()).isEqualTo(TECH_ID);
        assertThat(c.getAssignedEngineerId()).isEqualTo(ENGINEER_ID);
        assertThat(c.getSeverity()).isEqualTo(ComplaintSeverity.HIGH);
        ArgumentCaptor<ComplaintHistory> h = ArgumentCaptor.forClass(ComplaintHistory.class);
        verify(history).save(h.capture());
        assertThat(h.getValue().getFromStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(h.getValue().getToStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
    }

    @Test
    @DisplayName("assign: engineer cannot assign a technician from another DC")
    void assign_technicianNotInEngineerDc_rejected() {
        Complaint c = baseSubmitted();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(staff.getActiveTechnician(TECH_ID))
                .thenReturn(new StaffScopeView(TECH_ID, UserRole.TECHNICIAN, SUBDIV, OTHER_DC, true));

        assertThatThrownBy(() ->
                service.assign(engineer, 7L,
                        new AssignComplaintRequest(TECH_ID, ComplaintSeverity.LOW)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TECHNICIAN_NOT_IN_DC);
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("reassign: admin cross-DC re-points distribution_center_id and assigned_engineer_id")
    void reassign_admin_crossDc_repointsDcAndEngineer() {
        Complaint c = baseAssigned();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(staff.getActiveTechnician(TECH_ID))
                .thenReturn(new StaffScopeView(TECH_ID, UserRole.TECHNICIAN, SUBDIV, OTHER_DC, true));
        when(staff.findActiveEngineerForDc(OTHER_DC))
                .thenReturn(Optional.of(new StaffScopeView(42L, UserRole.ENGINEER, SUBDIV, OTHER_DC, true)));

        service.reassign(admin, 7L, new ReassignComplaintRequest(TECH_ID, "Workload balancing"));

        assertThat(c.getAssignedTechnicianId()).isEqualTo(TECH_ID);
        assertThat(c.getDistributionCenterId()).isEqualTo(OTHER_DC);
        assertThat(c.getAssignedEngineerId()).isEqualTo(42L);
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED); // unchanged
        verify(history).save(any());
    }

    @Test
    @DisplayName("reassign: refuses a complaint that has not yet been assigned")
    void reassign_unassignedComplaint_rejected() {
        Complaint c = baseSubmitted(); // no technician yet
        when(complaints.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() ->
                service.reassign(engineer, 7L, new ReassignComplaintRequest(TECH_ID, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
    }

    private Complaint baseSubmitted() {
        return Complaint.builder()
                .id(7L)
                .ticketNo("MH20260600000007")
                .consumerMasterId(99L)
                .contactMobile("+919999999999")
                .categoryId(3L)
                .description("x")
                .distributionCenterId(DC)
                .status(ComplaintStatus.SUBMITTED)
                .slaBreached(false)
                .build();
    }

    private Complaint baseAssigned() {
        Complaint c = baseSubmitted();
        c.setStatus(ComplaintStatus.ASSIGNED);
        c.setAssignedTechnicianId(50L);
        c.setAssignedEngineerId(ENGINEER_ID);
        c.setSeverity(ComplaintSeverity.MEDIUM);
        return c;
    }
}

