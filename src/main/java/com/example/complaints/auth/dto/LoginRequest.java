package com.example.complaints.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 50) String employeeId,
        @NotBlank @Size(min = 1, max = 200) String password
) {}

