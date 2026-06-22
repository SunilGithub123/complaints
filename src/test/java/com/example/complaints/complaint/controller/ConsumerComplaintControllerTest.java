package com.example.complaints.complaint.controller;

import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.service.ComplaintCreationService;
import com.example.complaints.complaint.service.ComplaintReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsumerComplaintController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ConsumerComplaintControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ComplaintCreationService creation;
    @MockitoBean ComplaintReadService read;
    // ConsumerVerificationFilter is bean-injected by the slice even when addFilters=false.
    @MockitoBean JwtFactory jwtFactory;

    private final VerifiedConsumer caller =
            new VerifiedConsumer("MH00010001", 99L, "+919999999999");

    @BeforeEach
    void authenticate() {
        // addFilters=false drops SecurityContextHolderFilter, so we install the principal directly.
        // (Same pattern as Stage 8b's StaffAuthControllerTest helper.)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, "n/a", AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /complaints — happy path returns 201 with ticketNo")
    void submit_happy_201() throws Exception {
        when(creation.submit(any(), any(), any())).thenReturn(new SubmitComplaintResponse(
                555L, "MH20260600000123", ComplaintStatus.SUBMITTED,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24), List.of()));

        String json = """
                { "consumerId":"MH00010001", "mobile":"+919999999999",
                  "categoryId":3, "description":"Power outage", "location":"Plot 17" }
                """;
        MockMultipartFile complaintPart = new MockMultipartFile(
                "complaint", "", APPLICATION_JSON.toString(), json.getBytes());
        MockMultipartFile image = new MockMultipartFile(
                "images", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/consumer/complaints")
                        .file(complaintPart)
                        .file(image)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketNo").value("MH20260600000123"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("POST /complaints — validation: blank consumerId → 400 VALIDATION_FAILED")
    void submit_blankConsumerId_400() throws Exception {
        String json = """
                { "consumerId":"", "mobile":"+919999999999",
                  "categoryId":3, "description":"Power outage" }
                """;
        MockMultipartFile complaintPart = new MockMultipartFile(
                "complaint", "", APPLICATION_JSON.toString(), json.getBytes());

        mockMvc.perform(multipart("/api/v1/consumer/complaints")
                        .file(complaintPart)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /complaints/{ticketNo} — happy path returns mapped detail")
    void get_happy_200() throws Exception {
        when(read.getOwnedByTicketNo(any(), eq("MH20260600000123"))).thenReturn(
                new ComplaintDetailResponse(
                        1L, "MH20260600000123", "MH00010001", "+919999999999",
                        3L, "Power outage", "Plot 17", ComplaintStatus.SUBMITTED,
                        OffsetDateTime.now(), OffsetDateTime.now().plusHours(24), List.of()));

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}", "MH20260600000123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNo").value("MH20260600000123"))
                .andExpect(jsonPath("$.data.consumerId").value("MH00010001"));
    }

    @Test
    @DisplayName("GET /complaints/{ticketNo} — foreign ticket → 403 COMPLAINT_NOT_OWNED_BY_CONSUMER")
    void get_foreignTicket_403() throws Exception {
        when(read.getOwnedByTicketNo(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.COMPLAINT_NOT_OWNED_BY_CONSUMER));

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}", "MH20260600000999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_OWNED_BY_CONSUMER"));
    }
}

