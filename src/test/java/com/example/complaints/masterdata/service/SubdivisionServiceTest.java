package com.example.complaints.masterdata.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.dto.SubdivisionRequest;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.mapper.SubdivisionMapper;
import com.example.complaints.masterdata.model.Subdivision;
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
    private SubdivisionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SubdivisionRepository.class);
        service = new SubdivisionService(repo, new SubdivisionMapper());
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
}

