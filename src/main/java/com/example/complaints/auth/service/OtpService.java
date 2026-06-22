package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.OtpSendRequest;
import com.example.complaints.auth.dto.OtpVerifyRequest;
import com.example.complaints.auth.dto.OtpVerifyResponse;
import com.example.complaints.auth.model.Otp;
import com.example.complaints.auth.model.OtpPurpose;
import com.example.complaints.auth.repository.OtpRepository;
import com.example.complaints.auth.security.JwtFactory;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.common.util.DateUtils;
import com.example.complaints.consumer.dto.ConsumerView;
import com.example.complaints.consumer.service.ConsumerLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Consumer-side OTP send + verify. v1 rules (TECHNICAL_DESIGN.md §6, §10):
 * <ul>
 *   <li>6-digit OTP, 5-minute TTL, BCrypt-hashed on disk — raw OTP is never persisted or logged.</li>
 *   <li>Per-mobile rate limit: max 5 sends per hour, with a 30-second cooldown between consecutive sends
 *       (regardless of purpose). Enforced via DB lookup so multi-pod deployments share state.</li>
 *   <li>Per-OTP verify cap: 5 attempts; on overflow the row is marked consumed and the consumer
 *       must request a fresh OTP.</li>
 *   <li>On successful verify: row marked consumed, and a 5-minute non-refreshable consumer
 *       verification JWT is issued carrying {@code consumerId}, {@code consumerMasterId}, {@code mobile}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository otpRepository;
    private final ConsumerLookupService consumerLookup;
    private final PasswordEncoder passwordEncoder;
    private final JwtFactory jwtFactory;
    private final SmsService smsService;
    private final OtpProperties props;

    @Transactional
    public void sendOtp(OtpSendRequest req) {
        ConsumerView consumer = consumerLookup.requireActiveByConsumerId(req.consumerId());

        enforceCooldown(req.mobile());
        enforceHourlyRateLimit(req.mobile());

        String rawOtp = generateOtp(props.length());
        Otp record = Otp.builder()
                .mobile(req.mobile())
                .otpHash(passwordEncoder.encode(rawOtp))
                .purpose(OtpPurpose.CONSUMER_VERIFY)
                .consumerId(consumer.consumerId())
                .expiresAt(Instant.now().plus(props.ttl()))
                .consumed(false)
                .attempts(0)
                .build();
        otpRepository.save(record);

        log.info("Issued OTP for consumerId={} mobile=***{} (last 2)",
                consumer.consumerId(), lastTwo(req.mobile()));
        // Deliberately delegated outside of the persistence pre-commit window — see SmsService Javadoc.
        // Sending is best-effort in v1 (console mock); MSG91 errors will be hardened in Phase 3 follow-up.
        deliver(req.mobile(), rawOtp);
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(OtpVerifyRequest req) {
        Otp record = otpRepository
                .findFirstByMobileAndPurposeAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        req.mobile(), OtpPurpose.CONSUMER_VERIFY, Instant.now())
                .orElseThrow(() -> new BusinessException(ErrorCode.OTP_EXPIRED));

        if (record.getAttempts() >= props.maxAttempts()) {
            record.setConsumed(true);
            throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
        }

        if (!passwordEncoder.matches(req.otp(), record.getOtpHash())) {
            record.setAttempts(record.getAttempts() + 1);
            if (record.getAttempts() >= props.maxAttempts()) {
                record.setConsumed(true);
                throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
            }
            throw new BusinessException(ErrorCode.OTP_INVALID);
        }

        // Cross-check: the OTP we matched was issued against the consumer ID supplied in the body.
        // Prevents a consumer from using another consumer's pending OTP on the same mobile.
        if (!req.consumerId().equals(record.getConsumerId())) {
            throw new BusinessException(ErrorCode.OTP_INVALID);
        }

        ConsumerView consumer = consumerLookup.requireActiveByConsumerId(req.consumerId());
        record.setConsumed(true);

        var issued = jwtFactory.issueConsumerVerificationToken(
                consumer.consumerId(), consumer.id(), req.mobile());

        return new OtpVerifyResponse(issued.jwt(), DateUtils.toIst(issued.expiresAt()));
    }

    private void enforceCooldown(String mobile) {
        otpRepository.findFirstByMobileOrderByCreatedAtDesc(mobile).ifPresent(latest -> {
            Instant earliestNextSend = latest.getCreatedAt().plusSeconds(props.cooldownSeconds());
            if (Instant.now().isBefore(earliestNextSend)) {
                throw new BusinessException(ErrorCode.OTP_COOLDOWN);
            }
        });
    }

    private void enforceHourlyRateLimit(String mobile) {
        long sentInLastHour = otpRepository.countByMobileAndCreatedAtAfter(
                mobile, Instant.now().minus(Duration.ofHours(1)));
        if (sentInLastHour >= props.maxPerMobilePerHour()) {
            throw new BusinessException(ErrorCode.OTP_RATE_LIMIT);
        }
    }

    private void deliver(String mobile, String otp) {
        smsService.sendOtp(mobile, otp);
    }

    private static String generateOtp(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static String lastTwo(String mobile) {
        return mobile == null || mobile.length() < 2 ? "??" : mobile.substring(mobile.length() - 2);
    }
}



