package com.example.complaints.complaint.controller;

import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.common.exception.GlobalExceptionHandler;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.dto.ConsumerComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ConsumerComplaintListItemResponse;
import com.example.complaints.complaint.dto.CancelComplaintRequest;
import com.example.complaints.complaint.dto.FeedbackResponse;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.dto.SubmitFeedbackRequest;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.service.ComplaintCancellationService;
import com.example.complaints.complaint.service.ComplaintCreationService;
import com.example.complaints.complaint.service.ComplaintFeedbackService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsumerComplaintController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ConsumerComplaintControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ComplaintCreationService creation;
    @MockitoBean ComplaintReadService read;
    @MockitoBean ComplaintCancellationService cancellation;
    @MockitoBean ComplaintFeedbackService feedback;
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
    @DisplayName("GET /complaints/{ticketNo} — happy path returns enriched detail")
    void get_happy_200() throws Exception {
        when(read.getOwnedByTicketNo(any(), eq("MH20260600000123"))).thenReturn(
                new ComplaintDetailResponse(
                        1L, "MH20260600000123", "MH00010001", "+919999999999",
                        3L, null, "Power outage", "Plot 17",
                        ComplaintStatus.SUBMITTED, false,
                        OffsetDateTime.now(), OffsetDateTime.now().plusHours(24),
                        null, null, false, List.of()));

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}", "MH20260600000123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNo").value("MH20260600000123"))
                .andExpect(jsonPath("$.data.consumerId").value("MH00010001"))
                .andExpect(jsonPath("$.data.slaBreached").value(false));
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

    @Test
    @DisplayName("GET /complaints — paged tracking list returns PageResponse envelope")
    void list_happy_200() throws Exception {
        ConsumerComplaintListItemResponse row = new ConsumerComplaintListItemResponse(
                1L, "MH20260600000123", 3L, null, ComplaintStatus.RESOLVED, false,
                null, null, null, null, false);
        when(read.listOwned(any(), eq(null), any()))
                .thenReturn(new PageResponse<>(List.of(row), 0, 20, 1L, 1, List.of("createdAt: DESC")));

        mockMvc.perform(get("/api/v1/consumer/complaints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].ticketNo").value("MH20260600000123"))
                .andExpect(jsonPath("$.data.content[0].status").value("RESOLVED"));
    }

    @Test
    @DisplayName("GET /complaints/{ticketNo}/history — consumer-safe rows (no changedByUserId field)")
    void getHistory_happy_200() throws Exception {
        when(read.getOwnedHistory(any(), eq("MH20260600000123"))).thenReturn(List.of(
                new ConsumerComplaintHistoryEntryResponse(
                        11L, ComplaintStatus.SUBMITTED, ComplaintStatus.ASSIGNED,
                        "Assigned", OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}/history", "MH20260600000123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].toStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.data[0].changedByUserId").doesNotExist());
    }

    @Test
    @DisplayName("POST /complaints/{ticketNo}/cancel — happy path delegates and returns success envelope")
    void cancel_happy_200() throws Exception {
        doNothing().when(cancellation).cancel(any(), eq("MH20260600000123"), any(CancelComplaintRequest.class));

        mockMvc.perform(post("/api/v1/consumer/complaints/{t}/cancel", "MH20260600000123")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Issue self-resolved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(cancellation).cancel(any(), eq("MH20260600000123"), any(CancelComplaintRequest.class));
    }

    @Test
    @DisplayName("POST /complaints/{ticketNo}/cancel — non-SUBMITTED state → 409 COMPLAINT_NOT_IN_SUBMITTED_STATE")
    void cancel_wrongState_409() throws Exception {
        doThrow(new BusinessException(ErrorCode.COMPLAINT_NOT_IN_SUBMITTED_STATE))
                .when(cancellation).cancel(any(), anyString(), any(CancelComplaintRequest.class));

        mockMvc.perform(post("/api/v1/consumer/complaints/{t}/cancel", "MH20260600000999")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_IN_SUBMITTED_STATE"));
    }

    @Test
    @DisplayName("POST /complaints/{ticketNo}/feedback — happy path returns 201 with persisted FeedbackResponse")
    void feedback_happy_201() throws Exception {
        when(feedback.submit(any(), eq("MH20260600000007"), any(SubmitFeedbackRequest.class)))
                .thenReturn(new FeedbackResponse(101L, 5, "Great work!", OffsetDateTime.now()));

        mockMvc.perform(post("/api/v1/consumer/complaints/{t}/feedback", "MH20260600000007")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Great work!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    @DisplayName("POST /complaints/{ticketNo}/feedback — rating out of range → 400 VALIDATION_FAILED")
    void feedback_invalidRating_400() throws Exception {
        mockMvc.perform(post("/api/v1/consumer/complaints/{t}/feedback", "MH20260600000007")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /complaints/{ticketNo}/feedback — existing row returns 200 with payload")
    void getFeedback_existing_200() throws Exception {
        when(feedback.getOwned(any(), eq("MH20260600000007")))
                .thenReturn(new FeedbackResponse(101L, 4, "Quick fix", OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}/feedback", "MH20260600000007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.rating").value(4));
    }

    @Test
    @DisplayName("GET /complaints/{ticketNo}/feedback — no row yet returns 200 with data=null")
    void getFeedback_missing_200_null() throws Exception {
        when(feedback.getOwned(any(), eq("MH20260600000007"))).thenReturn(null);

        mockMvc.perform(get("/api/v1/consumer/complaints/{t}/feedback", "MH20260600000007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}

