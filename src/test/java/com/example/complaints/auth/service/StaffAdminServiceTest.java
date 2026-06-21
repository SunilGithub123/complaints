package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.CreateStaffRequest;
import com.example.complaints.auth.dto.ResetStaffPasswordResponse;
import com.example.complaints.auth.mapper.UserAccountMapper;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.RefreshTokenRepository;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.masterdata.service.SubdivisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffAdminServiceTest {

    private static final Long ADMIN_SUBDIV = 10L;
    private static final Long DC_ID = 22L;

    private UserAccountRepository users;
    private RefreshTokenRepository refreshTokens;
    private SubdivisionService subdivisions;
    private DistributionCenterService distributionCenters;
    private StaffAdminService service;

    private final AuthenticatedStaff admin =
            new AuthenticatedStaff(1L, "ADMIN001", UserRole.ADMIN, ADMIN_SUBDIV, null, false);

    @BeforeEach
    @SuppressWarnings("deprecation")
    void setUp() {
        users = mock(UserAccountRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        subdivisions = mock(SubdivisionService.class);
        distributionCenters = mock(DistributionCenterService.class);
        service = new StaffAdminService(
                users, refreshTokens, NoOpPasswordEncoder.getInstance(),
                new UserAccountMapper(), subdivisions, distributionCenters);

        when(subdivisions.requireActive(ADMIN_SUBDIV))
                .thenReturn(Subdivision.builder().id(ADMIN_SUBDIV).code("SUB-NSK-001").active(true).build());
        when(distributionCenters.requireActive(DC_ID))
                .thenReturn(DistributionCenter.builder()
                        .id(DC_ID).subdivisionId(ADMIN_SUBDIV).code("DC-NSK-001").active(true).build());
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> {
            UserAccount u = inv.getArgument(0);
            u.setId(101L);
            return u;
        });
    }

    @Test
    @DisplayName("create: happy path returns one-time temporary password and seeds the staff row")
    void create_success() {
        CreateStaffRequest req = new CreateStaffRequest(
                "ENG-NSK-007", "Test Engineer", UserRole.ENGINEER,
                "eng@example.in", "+919876543210", ADMIN_SUBDIV, DC_ID);

        ResetStaffPasswordResponse res = service.create(admin, req);

        assertThat(res.id()).isEqualTo(101L);
        assertThat(res.employeeId()).isEqualTo("ENG-NSK-007");
        assertThat(res.temporaryPassword()).hasSize(16);
    }

    @Test
    @DisplayName("create: ENGINEER without distributionCenterId → STAFF_ROLE_FIELDS_INVALID")
    void create_engineerWithoutDc() {
        CreateStaffRequest bad = new CreateStaffRequest(
                "ENG-X-001", "Test", UserRole.ENGINEER, null, null, ADMIN_SUBDIV, null);

        assertThatThrownBy(() -> service.create(admin, bad))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STAFF_ROLE_FIELDS_INVALID);
    }
}

