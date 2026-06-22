package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.dto.ComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ComplaintStaffDetailResponse;
import com.example.complaints.complaint.mapper.ComplaintMapper;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplaintStaffReadServiceTest {

    private ComplaintRepository complaints;
    private ComplaintImageRepository images;
    private ComplaintHistoryRepository history;
    private ComplaintScopeGuard scope;
    private ComplaintMapper mapper;
    private ComplaintStaffReadService service;

    private final AuthenticatedStaff engineer = new AuthenticatedStaff(
            1L, "ENG001", UserRole.ENGINEER, 100L, 10L, false);

    @BeforeEach
    void setUp() {
        complaints = mock(ComplaintRepository.class);
        images = mock(ComplaintImageRepository.class);
        history = mock(ComplaintHistoryRepository.class);
        scope = mock(ComplaintScopeGuard.class);
        mapper = mock(ComplaintMapper.class);
        service = new ComplaintStaffReadService(complaints, images, history, scope, mapper);
    }

    @Test
    @DisplayName("getById: returns mapped detail when in scope")
    void getById_happyPath() {
        Complaint c = baseSubmitted();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        when(images.findByComplaintIdOrderByIdAsc(7L)).thenReturn(List.of());
        doNothing().when(scope).requireInScope(any(), any());
        ComplaintStaffDetailResponse stub = mock(ComplaintStaffDetailResponse.class);
        when(mapper.toStaffDetailResponse(c, List.of())).thenReturn(stub);

        assertThat(service.getById(engineer, 7L)).isSameAs(stub);
    }

    @Test
    @DisplayName("getById: 404 when not found")
    void getById_notFound() {
        when(complaints.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(engineer, 7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_NOT_FOUND);
    }

    @Test
    @DisplayName("getHistory: returns mapped rows in chronological order")
    void getHistory_happyPath() {
        Complaint c = baseSubmitted();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        doNothing().when(scope).requireInScope(any(), any());
        ComplaintHistory row = ComplaintHistory.builder()
                .id(1L).complaintId(7L).toStatus(ComplaintStatus.SUBMITTED).build();
        when(history.findByComplaintIdOrderByChangedAtAsc(7L)).thenReturn(List.of(row));
        ComplaintHistoryEntryResponse mapped = new ComplaintHistoryEntryResponse(
                1L, null, ComplaintStatus.SUBMITTED, null, null, null);
        when(mapper.toHistoryResponse(row)).thenReturn(mapped);

        assertThat(service.getHistory(engineer, 7L)).containsExactly(mapped);
    }

    @Test
    @DisplayName("getHistory: out-of-scope caller is blocked before any data is read")
    void getHistory_outOfScope() {
        Complaint c = baseSubmitted();
        when(complaints.findById(7L)).thenReturn(Optional.of(c));
        doThrow(new BusinessException(ErrorCode.COMPLAINT_OUT_OF_SCOPE))
                .when(scope).requireInScope(any(), any());

        assertThatThrownBy(() -> service.getHistory(engineer, 7L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_OUT_OF_SCOPE);
    }

    private Complaint baseSubmitted() {
        return Complaint.builder()
                .id(7L).ticketNo("MH20260600000007").consumerMasterId(99L)
                .contactMobile("+919999999999").categoryId(3L).description("x")
                .distributionCenterId(10L).status(ComplaintStatus.SUBMITTED).slaBreached(false)
                .build();
    }
}

