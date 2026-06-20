package com.example.complaints.auth.security;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.ErrorResponse;
import com.example.complaints.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

/**
 * If the authenticated staff has {@code passwordResetRequired=true}, refuse every request
 * except the small allow-list ({@code /change-password}, {@code /logout}, {@code /me}).
 *
 * <p>Runs AFTER {@link JwtAuthFilter}.</p>
 */
@Component
public class PasswordResetRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/staff/auth/change-password",
            "/api/v1/staff/auth/logout",
            "/api/v1/staff/me"
    );

    private final ObjectMapper objectMapper;

    public PasswordResetRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedStaff staff
                && staff.passwordResetRequired()
                && !ALLOWED_PATHS.contains(req.getRequestURI())) {

            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorResponse err = ErrorResponse.of(
                    ErrorCode.PASSWORD_RESET_REQUIRED.name(),
                    ErrorCode.PASSWORD_RESET_REQUIRED.defaultMessage());
            objectMapper.writeValue(res.getOutputStream(), ApiResponse.error(err));
            return;
        }
        chain.doFilter(req, res);
    }
}

