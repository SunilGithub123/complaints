package com.example.complaints.masterdata.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.masterdata.dto.ComplaintCategoryRequest;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.mapper.ComplaintCategoryMapper;
import com.example.complaints.masterdata.model.ComplaintCategory;
import com.example.complaints.masterdata.repository.ComplaintCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplaintCategoryServiceTest {

    private ComplaintCategoryRepository repo;
    private ComplaintCategoryService service;

    @BeforeEach
    void setUp() {
        repo = mock(ComplaintCategoryRepository.class);
        service = new ComplaintCategoryService(repo, new ComplaintCategoryMapper());
        when(repo.save(any(ComplaintCategory.class))).thenAnswer(inv -> {
            ComplaintCategory c = inv.getArgument(0);
            c.setId(7L);
            return c;
        });
    }

    @Test
    @DisplayName("create: happy path persists category with sla hours and active=true")
    void create_success() {
        when(repo.existsByCode("NEW_CAT")).thenReturn(false);

        ComplaintCategoryResponse res = service.create(
                new ComplaintCategoryRequest("NEW_CAT", "New Category", 12));

        assertThat(res.id()).isEqualTo(7L);
        assertThat(res.slaHours()).isEqualTo(12);
        assertThat(res.active()).isTrue();
    }

    @Test
    @DisplayName("create: duplicate code throws CATEGORY_CODE_TAKEN")
    void create_duplicateCode() {
        when(repo.existsByCode("POWER_OUTAGE")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new ComplaintCategoryRequest("POWER_OUTAGE", "Dup", 24)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CATEGORY_CODE_TAKEN);
    }

    @Test
    @DisplayName("listActive: delegates to findByActiveTrue and maps each row")
    void listActive_filtersActiveOnly() {
        ComplaintCategory active = ComplaintCategory.builder()
                .id(1L).code("POWER_OUTAGE").name("Power Outage").slaHours(8).active(true).build();
        when(repo.findByActiveTrue(org.springframework.data.domain.PageRequest.of(0, 20)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(active)));

        var page = service.listActive(org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).code()).isEqualTo("POWER_OUTAGE");
    }
}

