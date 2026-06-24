package com.example.complaints.complaint.controller;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.service.ComplaintClosureService;
import com.example.complaints.complaint.service.ComplaintResolutionService;
import com.example.complaints.complaint.service.ComplaintSearchService;
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
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @MockitoBean ComplaintClosureService closure;
    @MockitoBean ComplaintSearchService search;
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

    @Test
    @DisplayName("GET /technician/complaints: returns PageResponse of complaints assigned to me")
    void list_success() throws Exception {
        PageResponse<ComplaintListItemResponse> page = new PageResponse<>(
                java.util.List.of(new ComplaintListItemResponse(
                        7L, "MH20260600000007", 3L, ComplaintSeverity.HIGH,
                        ComplaintStatus.IN_PROGRESS, false, 10L, 1L, 2L, "+919999999999",
                        null, null, null, null)),
                0, 20, 1, 1, java.util.List.of("createdAt,desc"));
        when(search.listForTechnician(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/technician/complaints").with(technician()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("POST close: technician closes own RESOLVED complaint → 200 + delegates to closure service")
    void close_success() throws Exception {
        doNothing().when(closure).closeByTechnician(any(), eq(7L), any());

        mockMvc.perform(post("/api/v1/technician/complaints/7/close").with(technician())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseComplaintRequest(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(closure).closeByTechnician(any(), eq(7L), any());
    }
}

