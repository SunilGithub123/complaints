package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.masterdata.service.DistributionCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplaintScopeGuardTest {

    private DistributionCenterService dcs;
    private ComplaintScopeGuard guard;

    @BeforeEach
    void setUp() {
        dcs = mock(DistributionCenterService.class);
        guard = new ComplaintScopeGuard(dcs);
    }

    @Test
    @DisplayName("engineer passes when complaint DC matches their own DC")
    void engineer_sameDc_passes() {
        AuthenticatedStaff eng = new AuthenticatedStaff(1L, "E", UserRole.ENGINEER, 100L, 10L, false);
        assertThatCode(() -> guard.requireInScope(eng, complaintInDc(10L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("admin from another subdivision is blocked")
    void admin_otherSubdivision_blocked() {
        AuthenticatedStaff admin = new AuthenticatedStaff(2L, "A", UserRole.ADMIN, 100L, null, false);
        when(dcs.getSubdivisionId(10L)).thenReturn(999L);

        assertThatThrownBy(() -> guard.requireInScope(admin, complaintInDc(10L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPLAINT_OUT_OF_SCOPE);
    }

    private Complaint complaintInDc(Long dcId) {
        return Complaint.builder()
                .id(1L).ticketNo("T").consumerMasterId(1L).contactMobile("x")
                .categoryId(1L).description("x").distributionCenterId(dcId)
                .status(ComplaintStatus.SUBMITTED).slaBreached(false)
                .build();
    }
}

