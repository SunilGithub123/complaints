package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.CreateStaffRequest;
import com.example.complaints.auth.dto.ResetStaffPasswordResponse;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.service.StaffAdminService;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Representative MockMvc test for the Admin → Staff layer. Covers:
 *   • happy create → 200 + envelope payload exposing the one-time temp password
 *   • request validation → 400 + envelope error code
 *
 * Authorization (ADMIN role gate on /api/v1/admin/**) is enforced by SecurityConfig and
 * verified end-to-end in the boot IT, not here.
 */
@WebMvcTest(StaffAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class StaffAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean StaffAdminService staffAdminService;
    // Filter beans pulled in by @WebMvcTest need JwtFactory to construct.
    @MockitoBean JwtFactory jwtFactory;

    private RequestPostProcessor authedAdmin() {
        return user(new AuthenticatedStaff(1L, "ADMIN001", UserRole.ADMIN, 10L, null, false));
    }

    @Test
    @DisplayName("POST create: happy path returns 200 with the temporary password in the envelope")
    void create_success() throws Exception {
        when(staffAdminService.create(any(), any(CreateStaffRequest.class)))
                .thenReturn(new ResetStaffPasswordResponse(101L, "ENG-NSK-007", "Abc123Def456Ghi7"));

        mockMvc.perform(post("/api/v1/admin/staff").with(authedAdmin())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                "ENG-NSK-007", "Test Engineer", UserRole.ENGINEER,
                                "eng@example.in", "+919876543210", 10L, 22L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.employeeId").value("ENG-NSK-007"))
                .andExpect(jsonPath("$.data.temporaryPassword").value("Abc123Def456Ghi7"));
    }

    @Test
    @DisplayName("POST create: blank employeeId → 400 + VALIDATION_FAILED")
    void create_validationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/admin/staff").with(authedAdmin())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                "", "Test", UserRole.TECHNICIAN, null, null, 10L, 22L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}

