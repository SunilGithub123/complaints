package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.StaffDirectoryEntryResponse;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffDirectoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class StaffDirectoryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean StaffLookupService lookup;
    @MockitoBean JwtFactory jwtFactory;

    private RequestPostProcessor anyStaff() {
        return user(new AuthenticatedStaff(1L, "ENG001", UserRole.ENGINEER, 100L, 10L, false));
    }

    @Test
    @DisplayName("GET /{id}: returns directory entry envelope")
    void getById_success() throws Exception {
        when(lookup.getDirectoryEntry(eq(42L))).thenReturn(new StaffDirectoryEntryResponse(
                42L, "ENG-007", "Asha Patel", UserRole.ENGINEER, 100L, 10L, true));

        mockMvc.perform(get("/api/v1/staff/users/42").with(anyStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.employeeId").value("ENG-007"))
                .andExpect(jsonPath("$.data.fullName").value("Asha Patel"))
                .andExpect(jsonPath("$.data.role").value("ENGINEER"))
                .andExpect(jsonPath("$.data.passwordResetRequired").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}: unknown id → 404 STAFF_NOT_FOUND")
    void getById_notFound() throws Exception {
        when(lookup.getDirectoryEntry(eq(42L)))
                .thenThrow(new BusinessException(ErrorCode.STAFF_NOT_FOUND));

        mockMvc.perform(get("/api/v1/staff/users/42").with(anyStaff()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STAFF_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET ?ids=: batch returns list envelope")
    void getMany_success() throws Exception {
        when(lookup.getDirectoryEntries(List.of(1L, 2L))).thenReturn(List.of(
                new StaffDirectoryEntryResponse(1L, "A", "Alice", UserRole.ENGINEER, 100L, 10L, true),
                new StaffDirectoryEntryResponse(2L, "B", "Bob",   UserRole.TECHNICIAN, 100L, 10L, true)));

        mockMvc.perform(get("/api/v1/staff/users?ids=1,2").with(anyStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[1].userId").value(2));
    }

    @Test
    @DisplayName("GET ?role=TECHNICIAN: paged search returns PageResponse envelope")
    void search_success() throws Exception {
        PageResponse<StaffDirectoryEntryResponse> page = new PageResponse<>(
                List.of(new StaffDirectoryEntryResponse(
                        2L, "T-007", "Tara Tech", UserRole.TECHNICIAN, 100L, 10L, true)),
                0, 20, 1, 1, List.of("createdAt,desc"));
        when(lookup.searchDirectory(any(), eq(UserRole.TECHNICIAN), eq(null), eq(true), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/staff/users?role=TECHNICIAN&active=true").with(anyStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].employeeId").value("T-007"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}

