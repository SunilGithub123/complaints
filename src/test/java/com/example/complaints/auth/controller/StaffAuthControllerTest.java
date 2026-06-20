package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.LoginRequest;
import com.example.complaints.auth.dto.LoginResponse;
import com.example.complaints.auth.dto.StaffSummaryResponse;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.service.StaffAuthService;
import com.example.complaints.auth.security.JwtFactory;
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
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffAuthController.class)
@AutoConfigureMockMvc(addFilters = false)        // bypass the security filter chain; we test the controller directly
@Import(GlobalExceptionHandler.class)
class StaffAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean StaffAuthService authService;
    // @WebMvcTest still instantiates Filter beans (JwtAuthFilter, PasswordResetRequiredFilter)
    // even with addFilters=false; mock their dependency so the slice can construct them.
    @MockitoBean JwtFactory jwtFactory;

    @Test
    @DisplayName("POST /login: happy path returns 200 + envelope with access token")
    void login_success() throws Exception {
        var summary = new StaffSummaryResponse(
                1L, "ADMIN001", "Bootstrap Admin", UserRole.ADMIN, 10L, null, true, true);
        var response = new LoginResponse(
                "ACCESS-JWT", OffsetDateTime.now().plusMinutes(30),
                "REFRESH-JWT", OffsetDateTime.now().plusDays(7),
                true, summary);
        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/staff/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ADMIN001", "ChangeMe!123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("ACCESS-JWT"))
                .andExpect(jsonPath("$.data.passwordResetRequired").value(true))
                .andExpect(jsonPath("$.data.staff.employeeId").value("ADMIN001"));
    }

    @Test
    @DisplayName("POST /login: bad credentials returns 401 + envelope with BAD_CREDENTIALS code")
    void login_badCredentials() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.BAD_CREDENTIALS));

        mockMvc.perform(post("/api/v1/staff/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ADMIN001", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
    }
}
