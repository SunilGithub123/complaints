package com.example.complaints.complaint.controller;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ComplaintStaffDetailResponse;
import com.example.complaints.complaint.dto.RejectComplaintRequest;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.service.ComplaintAssignmentService;
import com.example.complaints.complaint.service.ComplaintClosureService;
import com.example.complaints.complaint.service.ComplaintSearchService;
import com.example.complaints.complaint.service.ComplaintStaffReadService;
import com.example.complaints.complaint.service.ComplaintTriageService;
import com.example.complaints.common.dto.PageResponse;
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
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @MockitoBean ComplaintStaffReadService read;
    @MockitoBean ComplaintClosureService closure;
    @MockitoBean ComplaintSearchService search;
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

    @Test
    @DisplayName("GET {id}: returns staff detail envelope")
    void getById_success() throws Exception {
        ComplaintStaffDetailResponse stub = new ComplaintStaffDetailResponse(
                7L, "MH20260600000007", 99L, "+919999999999", 3L, ComplaintSeverity.HIGH,
                "desc", "loc", 10L, 1L, 2L, null, ComplaintStatus.ASSIGNED, false,
                null, null, null, null, null, null, null, null, null, 1L, java.util.List.of());
        when(read.getById(any(), eq(7L))).thenReturn(stub);

        mockMvc.perform(get("/api/v1/staff/complaints/7").with(engineer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketNo").value("MH20260600000007"))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assignedTechnicianId").value(2));
    }

    @Test
    @DisplayName("POST close: delegates to closure service and returns the post-close detail")
    void close_success() throws Exception {
        doNothing().when(closure).close(any(), eq(7L), any(CloseComplaintRequest.class));
        ComplaintStaffDetailResponse closedDetail = new ComplaintStaffDetailResponse(
                7L, "MH20260600000007", 99L, "+919999999999", 3L, ComplaintSeverity.HIGH,
                "desc", "loc", 10L, 1L, 2L, null, ComplaintStatus.CLOSED, false,
                null, null, null, null, null, null, null, null, null, 2L, java.util.List.of());
        when(read.getById(any(), eq(7L))).thenReturn(closedDetail);

        mockMvc.perform(post("/api/v1/staff/complaints/7/close").with(engineer())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseComplaintRequest(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.version").value(2));

        verify(closure).close(any(), eq(7L), any(CloseComplaintRequest.class));
    }

    @Test
    @DisplayName("GET /staff/complaints?status=ASSIGNED: returns PageResponse envelope")
    void list_success() throws Exception {
        PageResponse<ComplaintListItemResponse> page = new PageResponse<>(
                java.util.List.of(new ComplaintListItemResponse(
                        7L, "MH20260600000007", 3L, ComplaintSeverity.HIGH,
                        ComplaintStatus.ASSIGNED, false, 10L, 1L, 2L, "+919999999999",
                        null, null, null, null)),
                0, 20, 1, 1, java.util.List.of("createdAt,desc"));
        when(search.listForStaff(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/staff/complaints?status=ASSIGNED").with(engineer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].ticketNo").value("MH20260600000007"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}

