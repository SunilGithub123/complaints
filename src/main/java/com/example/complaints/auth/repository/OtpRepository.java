package com.example.complaints.auth.repository;

import com.example.complaints.auth.model.Otp;
import com.example.complaints.auth.model.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpRepository extends JpaRepository<Otp, Long> {

    /** Latest send for {@code mobile} regardless of purpose — used for the 30-s send cooldown. */
    Optional<Otp> findFirstByMobileOrderByCreatedAtDesc(String mobile);

    /** Count sends for {@code mobile} since {@code threshold} — used for the per-hour rate limit. */
    long countByMobileAndCreatedAtAfter(String mobile, Instant threshold);

    /** Latest non-consumed, non-expired OTP for the (mobile, purpose) pair — used at verify time. */
    Optional<Otp> findFirstByMobileAndPurposeAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String mobile, OtpPurpose purpose, Instant now);

    /** Periodic cleanup — deletes everything older than the supplied threshold. */
    @Modifying
    @Query("delete from Otp o where o.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}

