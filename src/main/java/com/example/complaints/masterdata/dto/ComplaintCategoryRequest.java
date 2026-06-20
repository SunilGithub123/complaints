package com.example.complaints.masterdata.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ComplaintCategoryRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "Code must be uppercase letters, digits, or underscores")
        String code,

        @NotBlank @Size(max = 200) String name,

        @Min(1) @Max(720) int slaHours          // 1 hr .. 30 days
) {}

