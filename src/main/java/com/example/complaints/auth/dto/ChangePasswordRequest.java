package com.example.complaints.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Both current and new passwords are required. The new password must satisfy a basic
 * policy — at least 10 chars, includes upper / lower / digit / special. Tighter policy
 * (history, blocklist) can land in a later phase via a config-driven validator.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank
        @Size(min = 10, max = 200)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "Password must include upper, lower, digit and a special character"
        )
        String newPassword
) {}

