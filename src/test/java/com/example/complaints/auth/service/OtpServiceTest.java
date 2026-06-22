package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.OtpSendRequest;
import com.example.complaints.auth.dto.OtpVerifyRequest;
import com.example.complaints.auth.dto.OtpVerifyResponse;
import com.example.complaints.auth.model.Otp;
import com.example.complaints.auth.model.OtpPurpose;
import com.example.complaints.auth.repository.OtpRepository;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.auth.security.JwtProperties;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.consumer.dto.ConsumerView;
import com.example.complaints.consumer.service.ConsumerLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceTest {

    private static final String CONSUMER_ID = "MH00010001";
    private static final String MOBILE      = "+919900000001";

    private OtpRepository otpRepository;
    private ConsumerLookupService consumerLookup;
    private SmsService sms;
    private OtpService service;
    private JwtFactory jwt;

    /** Holds the OTP captured by the SMS mock so the verify tests can reuse the same raw OTP. */
    private final AtomicReference<String> sentOtp = new AtomicReference<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);   // low cost = fast test

    @BeforeEach
    void setUp() {
        otpRepository = mock(OtpRepository.class);
        consumerLookup = mock(ConsumerLookupService.class);
        sms = mock(SmsService.class);
        JwtProperties jwtProps = new JwtProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5),
                "complaints-api",
                "test-secret-must-be-at-least-32-bytes-long-_-_-_");
        jwt = new JwtFactory(jwtProps);
        OtpProperties otpProps = new OtpProperties(6, Duration.ofMinutes(5), 5, 30, 5);
        service = new OtpService(otpRepository, consumerLookup, encoder, jwt, sms, otpProps);

        when(consumerLookup.requireActiveByConsumerId(CONSUMER_ID))
                .thenReturn(new ConsumerView(42L, CONSUMER_ID, "Ramesh Patil", MOBILE, 7L, true));
        when(otpRepository.findFirstByMobileOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(otpRepository.countByMobileAndCreatedAtAfter(any(), any())).thenReturn(0L);
        when(otpRepository.save(any(Otp.class))).thenAnswer(inv -> {
            Otp o = inv.getArgument(0);
            if (o.getCreatedAt() == null) o.setCreatedAt(Instant.now());
            return o;
        });
        sentOtp.set(null);
    }

    @Test
    @DisplayName("sendOtp: happy path persists a BCrypt-hashed OTP and delegates to SmsService")
    void sendOtp_happyPath() {
        ArgumentCaptor<Otp> captor = ArgumentCaptor.forClass(Otp.class);

        service.sendOtp(new OtpSendRequest(CONSUMER_ID, MOBILE));

        verify(otpRepository).save(captor.capture());
        Otp saved = captor.getValue();
        assertThat(saved.getMobile()).isEqualTo(MOBILE);
        assertThat(saved.getConsumerId()).isEqualTo(CONSUMER_ID);
        assertThat(saved.getPurpose()).isEqualTo(OtpPurpose.CONSUMER_VERIFY);
        assertThat(saved.getOtpHash()).isNotBlank().doesNotMatch("\\d{6}");
        assertThat(saved.isConsumed()).isFalse();
        assertThat(saved.getAttempts()).isZero();
        verify(sms).sendOtp(eq(MOBILE), any());
    }

    @Test
    @DisplayName("sendOtp: refuses with OTP_COOLDOWN when last send was within 30 s")
    void sendOtp_cooldown() {
        Otp recent = Otp.builder().mobile(MOBILE).createdAt(Instant.now().minusSeconds(5)).build();
        when(otpRepository.findFirstByMobileOrderByCreatedAtDesc(MOBILE))
                .thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.sendOtp(new OtpSendRequest(CONSUMER_ID, MOBILE)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OTP_COOLDOWN);
        verify(sms, never()).sendOtp(any(), any());
        verify(otpRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyOtp: happy path consumes the row and returns a consumer verification JWT")
    void verifyOtp_happyPath() {
        // Capture the raw OTP issued by sendOtp via the SMS mock so we can replay it.
        doCaptureOtpOnSend();
        service.sendOtp(new OtpSendRequest(CONSUMER_ID, MOBILE));
        String raw = sentOtp.get();
        Otp issued = lastSavedOtp();
        // Repository now returns the row we just saved when verify looks it up.
        when(otpRepository
                .findFirstByMobileAndPurposeAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(MOBILE), eq(OtpPurpose.CONSUMER_VERIFY), any()))
                .thenReturn(Optional.of(issued));

        OtpVerifyResponse resp = service.verifyOtp(new OtpVerifyRequest(CONSUMER_ID, MOBILE, raw));

        assertThat(resp.verificationToken()).isNotBlank();
        assertThat(resp.expiresAt()).isAfter(Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime());
        assertThat(issued.isConsumed()).isTrue();
        // Token parses + carries the cmid claim from the consumer view.
        var claims = jwt.parse(resp.verificationToken());
        assertThat(claims.getSubject()).isEqualTo(CONSUMER_ID);
        assertThat(claims.get(JwtFactory.CLAIM_CONSUMER_MASTER_ID, Long.class)).isEqualTo(42L);
        assertThat(claims.get(JwtFactory.CLAIM_CONSUMER_MOBILE, String.class)).isEqualTo(MOBILE);
    }

    @Test
    @DisplayName("verifyOtp: wrong code increments attempts and throws OTP_INVALID")
    void verifyOtp_wrongCode() {
        Otp issued = Otp.builder()
                .id(1L).mobile(MOBILE).consumerId(CONSUMER_ID)
                .otpHash(encoder.encode("000000"))
                .purpose(OtpPurpose.CONSUMER_VERIFY)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .attempts(0).consumed(false)
                .createdAt(Instant.now())
                .build();
        when(otpRepository
                .findFirstByMobileAndPurposeAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(MOBILE), eq(OtpPurpose.CONSUMER_VERIFY), any()))
                .thenReturn(Optional.of(issued));

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest(CONSUMER_ID, MOBILE, "999999")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OTP_INVALID);
        assertThat(issued.getAttempts()).isEqualTo(1);
        assertThat(issued.isConsumed()).isFalse();
    }

    // ----- helpers -----

    private void doCaptureOtpOnSend() {
        org.mockito.Mockito.doAnswer(inv -> {
            sentOtp.set(inv.getArgument(1));
            return null;
        }).when(sms).sendOtp(any(), any());
    }

    private Otp lastSavedOtp() {
        ArgumentCaptor<Otp> captor = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepository).save(captor.capture());
        return captor.getValue();
    }
}

