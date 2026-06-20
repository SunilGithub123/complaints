package com.example.complaints.masterdata.service;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.dto.DistributionCenterRequest;
import com.example.complaints.masterdata.dto.DistributionCenterResponse;
import com.example.complaints.masterdata.mapper.DistributionCenterMapper;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributionCenterServiceTest {

    private DistributionCenterRepository repo;
    private SubdivisionService subdivisions;
    private DistributionCenterService service;

    private static final Long ADMIN_SUBDIV = 10L;
    private final AuthenticatedStaff admin =
            new AuthenticatedStaff(1L, "ADMIN001", UserRole.ADMIN, ADMIN_SUBDIV, null, false);

    @BeforeEach
    void setUp() {
        repo = mock(DistributionCenterRepository.class);
        subdivisions = mock(SubdivisionService.class);
        service = new DistributionCenterService(repo, new DistributionCenterMapper(), subdivisions);
        when(repo.save(any(DistributionCenter.class))).thenAnswer(inv -> {
            DistributionCenter dc = inv.getArgument(0);
            dc.setId(99L);
            return dc;
        });
        Subdivision active = Subdivision.builder().id(ADMIN_SUBDIV).code("SUB-NSK-001").active(true).build();
        when(subdivisions.requireActive(ADMIN_SUBDIV)).thenReturn(active);
    }

    @Test
    @DisplayName("create: happy path under admin's own subdivision")
    void create_success() {
        when(repo.existsByCode("DC-NSK-009")).thenReturn(false);

        DistributionCenterResponse res = service.create(admin,
                new DistributionCenterRequest(ADMIN_SUBDIV, "DC-NSK-009", "New DC", "Addr"));

        assertThat(res.id()).isEqualTo(99L);
        assertThat(res.subdivisionId()).isEqualTo(ADMIN_SUBDIV);
        assertThat(res.active()).isTrue();
    }

    @Test
    @DisplayName("create: admin trying to create DC outside own subdivision → DC_NOT_IN_SCOPE")
    void create_outOfScope() {
        assertThatThrownBy(() -> service.create(admin,
                new DistributionCenterRequest(99L /* not my subdivision */, "DC-X-001", "X", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DC_NOT_IN_SCOPE);
    }
}

