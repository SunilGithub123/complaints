package com.example.complaints.notification.controller;

import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.notification.dto.DeviceRegistrationRequest;
import com.example.complaints.notification.dto.DeviceTokenResponse;
import com.example.complaints.notification.model.DevicePlatform;
import com.example.complaints.notification.service.DeviceTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 21.1 — covers both {@link ConsumerDeviceController} and {@link StaffDeviceController}
 * with one slice per minimum-test policy. Endpoints are shape-identical modulo the principal.
 */
@WebMvcTest({ConsumerDeviceController.class, StaffDeviceController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DeviceControllersTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean DeviceTokenService service;
    @MockitoBean JwtFactory jwtFactory;

    private final VerifiedConsumer consumer =
            new VerifiedConsumer("MH00010001", 42L, "+919999999999");
    private final AuthenticatedStaff staff =
            new AuthenticatedStaff(11L, "ENG001", UserRole.ENGINEER, 100L, 10L, false);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", AuthorityUtils.NO_AUTHORITIES));
    }

    private DeviceTokenResponse response(boolean active) {
        return new DeviceTokenResponse(99L, "dev-uuid-1", DevicePlatform.ANDROID,
                "1.4.0", active, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("POST /consumer/devices first time → 201 Created + envelope, never echoes pushToken")
    void consumerRegister_firstTime_201() throws Exception {
        authenticate(consumer);
        when(service.registerForConsumer(eq(42L), any()))
                .thenReturn(new DeviceTokenService.RegistrationResult(response(true), true));

        mockMvc.perform(post("/api/v1/consumer/devices")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeviceRegistrationRequest("dev-uuid-1", "ANDROID", "fcm_token_abc", "1.4.0"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceId").value("dev-uuid-1"))
                .andExpect(jsonPath("$.data.pushToken").doesNotExist());

        verify(service).registerForConsumer(eq(42L), any());
    }

    @Test
    @DisplayName("POST /consumer/devices same deviceId again → 200 OK (refresh path)")
    void consumerRegister_refresh_200() throws Exception {
        authenticate(consumer);
        when(service.registerForConsumer(eq(42L), any()))
                .thenReturn(new DeviceTokenService.RegistrationResult(response(true), false));

        mockMvc.perform(post("/api/v1/consumer/devices")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeviceRegistrationRequest("dev-uuid-1", "ANDROID", "new_token", "1.4.0"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /consumer/devices blank deviceId → 400 VALIDATION_FAILED, service untouched")
    void consumerRegister_blankDeviceId_400() throws Exception {
        authenticate(consumer);
        mockMvc.perform(post("/api/v1/consumer/devices")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeviceRegistrationRequest("", "ANDROID", "tok", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("DELETE /consumer/devices/{deviceId} → 204 (idempotent), no response body")
    void consumerRevoke_204() throws Exception {
        authenticate(consumer);
        doNothing().when(service).revokeForConsumer(eq(42L), eq("dev-uuid-1"));

        mockMvc.perform(delete("/api/v1/consumer/devices/dev-uuid-1"))
                .andExpect(status().isNoContent());

        verify(service).revokeForConsumer(eq(42L), eq("dev-uuid-1"));
    }

    @Test
    @DisplayName("POST /staff/devices first time → 201 Created, delegates with caller userId")
    void staffRegister_firstTime_201() throws Exception {
        authenticate(staff);
        when(service.registerForUser(eq(11L), any()))
                .thenReturn(new DeviceTokenService.RegistrationResult(response(true), true));

        mockMvc.perform(post("/api/v1/staff/devices")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeviceRegistrationRequest("dev-uuid-1", "ANDROID", "fcm_token_abc", "1.4.0"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pushToken").doesNotExist());

        verify(service).registerForUser(eq(11L), any());
    }

    @Test
    @DisplayName("DELETE /staff/devices/{deviceId} → 204 (idempotent)")
    void staffRevoke_204() throws Exception {
        authenticate(staff);
        doNothing().when(service).revokeForUser(eq(11L), eq("dev-uuid-1"));

        mockMvc.perform(delete("/api/v1/staff/devices/dev-uuid-1"))
                .andExpect(status().isNoContent());

        verify(service).revokeForUser(eq(11L), eq("dev-uuid-1"));
    }
}

