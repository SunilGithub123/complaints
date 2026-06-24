package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.OtpSendRequest;
import com.example.complaints.auth.dto.OtpVerifyRequest;
import com.example.complaints.auth.dto.OtpVerifyResponse;
import com.example.complaints.auth.service.OtpService;
import com.example.complaints.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public consumer auth endpoints — OTP send + verify. The verify call returns a 5-minute,
 * non-refreshable consumer verification JWT that is then required on every
 * {@code /api/v1/consumer/**} endpoint (TECHNICAL_DESIGN.md §5.1).
 *
 * <p>Both endpoints are {@code permitAll} at the security chain level so the OTP flow itself
 * needs no prior credentials.</p>
 */
@RestController
@RequestMapping("/api/v1/auth/consumer")
@RequiredArgsConstructor
@Tag(name = "Consumer Auth", description = "Consumer per-action OTP verification")
public class ConsumerAuthController {

    private final OtpService otpService;

    @PostMapping("/otp/send")
    @Operation(operationId = "sendConsumerOtp",
            summary = "Send a 6-digit OTP to the supplied mobile",
            description = "Validates the Consumer ID against consumer_master, then issues a "
                    + "BCrypt-hashed OTP (raw never persisted or logged). Enforces a 30-second "
                    + "per-mobile cooldown and a max 5 sends per mobile per hour.")
    public ResponseEntity<ApiResponse<Void>> sendOtp(@Valid @RequestBody OtpSendRequest req) {
        otpService.sendOtp(req);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/otp/verify")
    @Operation(operationId = "verifyConsumerOtp",
            summary = "Verify an OTP and receive a 5-minute consumer verification token",
            description = "On success, returns a non-refreshable 5-minute JWT carrying "
                    + "consumerId, consumerMasterId, and the OTP-verified mobile. Use it on every "
                    + "/api/v1/consumer/** call. After expiry, repeat the send/verify cycle.")
    public ResponseEntity<ApiResponse<OtpVerifyResponse>> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(otpService.verifyOtp(req)));
    }
}

