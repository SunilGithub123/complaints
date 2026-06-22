package com.example.complaints.consumer.dto;

/**
 * Cross-module view of a consumer-master row. Used by {@code auth} (OTP send / verify) and the
 * future {@code complaint} module (resolve DC at submission). Records, not entities, cross
 * module boundaries — see ArchUnit {@code PackageBoundaryTest}.
 */
public record ConsumerView(
        Long id,
        String consumerId,
        String name,
        String mobile,
        Long distributionCenterId,
        boolean active
) {
}

