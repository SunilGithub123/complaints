package com.example.complaints.auth.dto;

/**
 * Returned <strong>once</strong> from create-staff and reset-password endpoints — the only
 * place the temporary plaintext password is ever surfaced. Admin must communicate it to
 * the user out-of-band; the staff member is forced to change it on first login
 * ({@code passwordResetRequired = true}).
 *
 * <p>This DTO MUST NEVER be logged. {@code GlobalExceptionHandler} and request/response
 * logging interceptors must skip the {@code POST /admin/staff} and
 * {@code POST /admin/staff/{id}/reset-password} routes' bodies.</p>
 */
public record ResetStaffPasswordResponse(
        Long id,
        String employeeId,
        String temporaryPassword
) {}

