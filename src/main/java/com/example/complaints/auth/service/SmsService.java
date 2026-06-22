package com.example.complaints.auth.service;

/**
 * Strategy for delivering OTPs to recipient mobiles. v1 implementations:
 * <ul>
 *   <li>{@link ConsoleSmsService} — dev profile only; prints to stdout with a clear DEV banner so
 *       no OTP ever lands in a real logger / log file.</li>
 *   <li>MSG91-backed impl — test / prod profile (Phase 3 follow-up; see ROADMAP §3).</li>
 * </ul>
 *
 * <p>Selected via {@code @ConditionalOnProperty}. Add {@code @Profile}-gated implementations
 * rather than hand-rolling a registry.</p>
 */
public interface SmsService {

    /**
     * Sends {@code otp} to {@code mobile}. Implementations must not log the OTP itself.
     *
     * @throws SmsDeliveryException when the provider rejects the request after retries
     */
    void sendOtp(String mobile, String otp);
}

