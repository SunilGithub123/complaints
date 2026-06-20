package com.example.complaints.masterdata.dto;

import java.time.OffsetDateTime;

public record SubdivisionResponse(
        Long id,
        String code,
        String name,
        String district,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}

