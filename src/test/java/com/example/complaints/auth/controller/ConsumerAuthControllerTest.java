package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.OtpSendRequest;
import com.example.complaints.auth.dto.OtpVerifyRequest;
import com.example.complaints.auth.dto.OtpVerifyResponse;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.service.OtpService;
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
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsumerAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ConsumerAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean OtpService otpService;
    // ConsumerVerificationFilter is instantiated by the slice even with addFilters=false.
    @MockitoBean JwtFactory jwtFactory;

    @Test
    @DisplayName("POST /otp/send: happy path returns 200 + empty envelope")
    void sendOtp_success() throws Exception {
        mockMvc.perform(post("/api/v1/auth/consumer/otp/send")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OtpSendRequest("MH00010001", "+919900000001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /otp/send: CONSUMER_NOT_FOUND surfaces as 404 with code in envelope")
    void sendOtp_consumerNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.CONSUMER_NOT_FOUND))
                .when(otpService).sendOtp(any());

        mockMvc.perform(post("/api/v1/auth/consumer/otp/send")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OtpSendRequest("MH-MISSING", "+919900000001"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONSUMER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /otp/verify: happy path returns 200 + token + expiresAt")
    void verifyOtp_success() throws Exception {
        var expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).plusMinutes(5);
        when(otpService.verifyOtp(any()))
                .thenReturn(new OtpVerifyResponse("CONSUMER-JWT", expiresAt));

        mockMvc.perform(post("/api/v1/auth/consumer/otp/verify")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OtpVerifyRequest("MH00010001", "+919900000001", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationToken").value("CONSUMER-JWT"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
    }
}

