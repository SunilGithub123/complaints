package com.example.complaints.masterdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create or update payload for a {@code Subdivision}.
 * {@code code} must match {@code [A-Z0-9-]+} (e.g. {@code SUB-NSK-001}).
 */
public record SubdivisionRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Z0-9-]+$", message = "Code must be uppercase letters, digits, or hyphens")
        String code,

        @NotBlank @Size(max = 200) String name,

        @Size(max = 100) String district
) {}

