package com.example.complaints.complaint.controller;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.RejectComplaintRequest;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.service.ComplaintAssignmentService;
import com.example.complaints.complaint.service.ComplaintTriageService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Representative MockMvc test for the Engineer/Admin complaint-management endpoints. Path-level
 * role enforcement (ENGINEER/ADMIN only) is wired in SecurityConfig and verified end-to-end by
 * the boot IT; here we cover happy + validation paths and service wiring.
 */
@WebMvcTest(StaffComplaintController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class StaffComplaintControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ComplaintAssignmentService assignment;
    @MockitoBean ComplaintTriageService triage;
    @MockitoBean JwtFactory jwtFactory;

    private RequestPostProcessor engineer() {
        return user(new AuthenticatedStaff(1L, "ENG001", UserRole.ENGINEER, 100L, 10L, false));
    }

    @Test
    @DisplayName("POST assign: happy path delegates to service and returns success envelope")
    void assign_success() throws Exception {
        doNothing().when(assignment).assign(any(), eq(7L), any(AssignComplaintRequest.class));

        mockMvc.perform(post("/api/v1/staff/complaints/7/assign").with(engineer())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignComplaintRequest(2L, ComplaintSeverity.HIGH))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(assignment).assign(any(), eq(7L), any(AssignComplaintRequest.class));
    }

    @Test
    @DisplayName("POST reject: blank reason → 400 VALIDATION_FAILED")
    void reject_blankReason() throws Exception {
        mockMvc.perform(post("/api/v1/staff/complaints/7/reject").with(engineer())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectComplaintRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}

