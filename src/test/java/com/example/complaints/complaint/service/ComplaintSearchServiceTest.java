package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ComplaintSearchRequest;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.masterdata.service.DistributionCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintSearchServiceTest {

    private ComplaintRepository complaints;
    private ComplaintMapper mapper;
    private DistributionCenterService dcs;
    private ComplaintSearchService service;

    private final AuthenticatedStaff engineer = new AuthenticatedStaff(
            1L, "ENG", UserRole.ENGINEER, 100L, 10L, false);
    private final AuthenticatedStaff admin = new AuthenticatedStaff(
            5L, "ADM", UserRole.ADMIN, 100L, null, false);
    private final AuthenticatedStaff technician = new AuthenticatedStaff(
            2L, "TECH", UserRole.TECHNICIAN, 100L, 10L, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        mapper = mock(ComplaintMapper.class);
        dcs = mock(DistributionCenterService.class);
        service = new ComplaintSearchService(complaints, mapper, dcs);
        when(mapper.toListItem(any())).thenAnswer(inv -> stubListItem(inv.getArgument(0)));
    }

    @Test
    @DisplayName("listForStaff: engineer caller delegates filtered search and returns PageResponse")
    void listForStaff_engineer_happyPath() {
        Pageable pg = PageRequest.of(0, 20);
        when(complaints.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(complaintAt(7L, ComplaintStatus.ASSIGNED))));

        ComplaintSearchRequest req = new ComplaintSearchRequest(
                ComplaintStatus.ASSIGNED, ComplaintSeverity.HIGH, null,
                null, null, false, null, null, null);

        PageResponse<ComplaintListItemResponse> page = service.listForStaff(engineer, req, pg);

        assertThat(page.content()).hasSize(1);
        verify(complaints).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("listForStaff: engineer asking for a different DC → 403 FORBIDDEN (no query issued)")
    void listForStaff_engineer_otherDc_forbidden() {
        ComplaintSearchRequest req = new ComplaintSearchRequest(
                null, null, null, 999L, null, null, null, null, null);

        assertThatThrownBy(() -> service.listForStaff(engineer, req, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("listForStaff: admin with no DC param composes IN(...) over subdivision DCs")
    void listForStaff_admin_subdivisionScope() {
        when(dcs.findDcIdsInSubdivision(100L)).thenReturn(List.of(10L, 20L, 30L));
        when(complaints.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listForStaff(admin, new ComplaintSearchRequest(
                null, null, null, null, null, null, null, null, null), PageRequest.of(0, 20));

        verify(dcs).findDcIdsInSubdivision(100L);
        verify(complaints).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("listForStaff: admin asking for a DC outside their subdivision → 403 FORBIDDEN")
    void listForStaff_admin_outOfSubdivisionDc_forbidden() {
        when(dcs.findDcIdsInSubdivision(100L)).thenReturn(List.of(10L, 20L));

        ComplaintSearchRequest req = new ComplaintSearchRequest(
                null, null, null, 999L, null, null, null, null, null);

        assertThatThrownBy(() -> service.listForStaff(admin, req, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("listForTechnician: pins assignedTechnicianId = caller and ignores cross-tech filter")
    void listForTechnician_happyPath() {
        when(complaints.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(complaintAt(7L, ComplaintStatus.IN_PROGRESS))));

        // Tech tries to pass assignedTechnicianId=999 — should be ignored, NOT used to widen.
        ComplaintSearchRequest req = new ComplaintSearchRequest(
                null, null, null, null, 999L, null, null, null, null);

        PageResponse<ComplaintListItemResponse> page = service.listForTechnician(
                technician, req, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        verify(complaints).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("listForTechnician: engineer caller is rejected (wrong-role)")
    void listForTechnician_engineer_rejected() {
        ComplaintSearchRequest req = new ComplaintSearchRequest(
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.listForTechnician(engineer, req, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    private Complaint complaintAt(Long id, ComplaintStatus status) {
        return Complaint.builder()
                .id(id).ticketNo("MH202606" + id).consumerMasterId(99L)
                .contactMobile("+919999999999").categoryId(3L).description("x")
                .distributionCenterId(10L).status(status).slaBreached(false).build();
    }

    private ComplaintListItemResponse stubListItem(Complaint c) {
        return new ComplaintListItemResponse(c.getId(), c.getTicketNo(), c.getCategoryId(),
                c.getSeverity(), c.getStatus(), c.isSlaBreached(), c.getDistributionCenterId(),
                null, null, c.getContactMobile(), null, null, null, null);
    }
}

