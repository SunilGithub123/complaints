package com.example.complaints.masterdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DistributionCenterRequest(
        @NotNull Long subdivisionId,

        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Z0-9-]+$", message = "Code must be uppercase letters, digits, or hyphens")
        String code,

        @NotBlank @Size(max = 200) String name,

        @Size(max = 1000) String address
) {}

