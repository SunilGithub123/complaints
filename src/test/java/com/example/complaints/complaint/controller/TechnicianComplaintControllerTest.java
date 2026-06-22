package com.example.complaints.complaint.controller;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.service.ComplaintResolutionService;
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

@WebMvcTest(TechnicianComplaintController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class TechnicianComplaintControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ComplaintResolutionService resolution;
    @MockitoBean JwtFactory jwtFactory;

    private RequestPostProcessor technician() {
        return user(new AuthenticatedStaff(2L, "TECH001", UserRole.TECHNICIAN, 100L, 10L, false));
    }

    @Test
    @DisplayName("POST start: delegates to service and returns success envelope")
    void start_success() throws Exception {
        doNothing().when(resolution).start(any(), eq(7L));

        mockMvc.perform(post("/api/v1/technician/complaints/7/start").with(technician()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(resolution).start(any(), eq(7L));
    }

    @Test
    @DisplayName("POST resolve: blank notes → 400 VALIDATION_FAILED")
    void resolve_blankNotes() throws Exception {
        mockMvc.perform(post("/api/v1/technician/complaints/7/resolve").with(technician())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResolveComplaintRequest("", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}

