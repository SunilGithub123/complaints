package com.example.complaints.masterdata.service;

import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.dto.SubdivisionRequest;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.mapper.SubdivisionMapper;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import com.example.complaints.masterdata.repository.SubdivisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubdivisionServiceTest {

    private SubdivisionRepository repo;
    private DistributionCenterRepository dcs;
    private StaffLookupService staffLookup;
    private SubdivisionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SubdivisionRepository.class);
        dcs = mock(DistributionCenterRepository.class);
        staffLookup = mock(StaffLookupService.class);
        service = new SubdivisionService(repo, new SubdivisionMapper(), dcs, staffLookup);
        when(repo.save(any(Subdivision.class))).thenAnswer(inv -> {
            Subdivision s = inv.getArgument(0);
            s.setId(42L);
            return s;
        });
    }

    @Test
    @DisplayName("create: happy path persists and returns the new row, active=true")
    void create_success() {
        when(repo.existsByCode("SUB-NSK-001")).thenReturn(false);

        SubdivisionResponse res = service.create(
                new SubdivisionRequest("SUB-NSK-001", "Nashik Rural", "Nashik"));

        assertThat(res.id()).isEqualTo(42L);
        assertThat(res.code()).isEqualTo("SUB-NSK-001");
        assertThat(res.active()).isTrue();
    }

    @Test
    @DisplayName("create: duplicate code throws SUBDIVISION_CODE_TAKEN")
    void create_duplicateCode() {
        when(repo.existsByCode("SUB-NSK-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new SubdivisionRequest("SUB-NSK-001", "Nashik Rural", "Nashik")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBDIVISION_CODE_TAKEN);
    }

    @Test
    @DisplayName("setActive(false): blocked when the subdivision still has active DCs")
    void deactivate_blockedByActiveDcs() {
        Subdivision live = Subdivision.builder().id(7L).code("SUB-X").active(true).build();
        when(repo.findById(7L)).thenReturn(java.util.Optional.of(live));
        when(dcs.existsBySubdivisionIdAndActiveTrue(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.setActive(7L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBDIVISION_HAS_ACTIVE_DCS);

        // Subdivision must not have flipped to inactive.
        assertThat(live.isActive()).isTrue();
    }
}

