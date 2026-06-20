package com.example.complaints.masterdata.controller;

import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.masterdata.dto.SubdivisionRequest;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.masterdata.service.SubdivisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Representative MockMvc test for the Master Data admin layer. Covers:
 *   • happy create → 200 + envelope payload
 *   • request validation → 400 + envelope error code
 *
 * Authorization (ADMIN role gate on /api/v1/admin/**) is enforced by SecurityConfig and
 * verified end-to-end in the boot IT, not here.
 */
@WebMvcTest(SubdivisionAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SubdivisionAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean SubdivisionService subdivisionService;
    // Other masterdata services share the slice; mock them so the read controller bean can construct.
    @MockitoBean DistributionCenterService dcService;
    @MockitoBean ComplaintCategoryService categoryService;
    // Filter beans pulled in by @WebMvcTest need JwtFactory to construct.
    @MockitoBean JwtFactory jwtFactory;

    @Test
    @DisplayName("POST create: happy path returns 200 with the new subdivision in the envelope")
    void create_success() throws Exception {
        when(subdivisionService.create(any(SubdivisionRequest.class)))
                .thenReturn(new SubdivisionResponse(
                        42L, "SUB-NSK-001", "Nashik Rural", "Nashik", true,
                        OffsetDateTime.now(), OffsetDateTime.now()));

        mockMvc.perform(post("/api/v1/admin/masterdata/subdivisions")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubdivisionRequest("SUB-NSK-001", "Nashik Rural", "Nashik"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.code").value("SUB-NSK-001"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("POST create: blank name → 400 + VALIDATION_FAILED")
    void create_validationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/admin/masterdata/subdivisions")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubdivisionRequest("SUB-NSK-001", "", "Nashik"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}

