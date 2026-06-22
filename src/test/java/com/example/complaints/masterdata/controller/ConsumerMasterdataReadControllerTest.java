package com.example.complaints.masterdata.controller;

import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsumerMasterdataReadController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ConsumerMasterdataReadControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ComplaintCategoryService categoryService;
    // ConsumerVerificationFilter is instantiated by the slice even with addFilters=false.
    @MockitoBean JwtFactory jwtFactory;

    @Test
    @DisplayName("GET /consumer/masterdata/categories → 200 with only active categories")
    void listActiveCategories_returnsPagedActiveOnly() throws Exception {
        when(categoryService.listActive(any())).thenReturn(new PageResponse<>(
                List.of(new ComplaintCategoryResponse(
                        1L, "POWER_OUTAGE", "Power Outage", 8, true,
                        OffsetDateTime.now(), OffsetDateTime.now())),
                0, 20, 1, 1, List.of()));

        mockMvc.perform(get("/api/v1/consumer/masterdata/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].code").value("POWER_OUTAGE"))
                .andExpect(jsonPath("$.data.content[0].active").value(true));
    }
}

