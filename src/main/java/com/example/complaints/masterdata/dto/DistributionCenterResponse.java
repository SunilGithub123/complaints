package com.example.complaints.masterdata.dto;

import java.time.OffsetDateTime;

public record DistributionCenterResponse(
        Long id,
        Long subdivisionId,
        String code,
        String name,
        String address,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}

