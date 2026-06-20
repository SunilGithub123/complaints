package com.example.complaints.masterdata.dto;

import java.time.OffsetDateTime;

public record ComplaintCategoryResponse(
        Long id,
        String code,
        String name,
        int slaHours,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}

