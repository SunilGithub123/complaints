package com.example.complaints.auth.security;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.ErrorResponse;
import com.example.complaints.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Extracts {@code Authorization: Bearer <jwt>} on staff routes, validates it, and pins an
 * {@link AuthenticatedStaff} into the Spring Security context.
 *
 * <p>Only acts on access tokens; refresh / consumer-verification tokens use other paths
 * ({@code /auth/refresh}, {@code ConsumerVerificationFilter}).</p>
 *
 * <p>This filter never throws to the chain — invalid / expired tokens produce a clean
 * 401 with an {@link ApiResponse} body, so the caller gets the same envelope shape they
 * already handle.</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER = "Bearer ";
    /**
     * Consumer routes carry a consumer-verification token, not a staff access token.
     * {@link ConsumerVerificationFilter} handles them; we must not intercept them here
     * or we will reject the consumer JWT as "not an access token" before the right filter
     * ever sees it.
     */
    private static final String CONSUMER_PATH_PREFIX = "/api/v1/consumer/";

    private final JwtFactory jwtFactory;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtFactory jwtFactory, ObjectMapper objectMapper) {
        this.jwtFactory = jwtFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(CONSUMER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(req, res);
            return;
        }

        String jwt = header.substring(BEARER.length()).trim();
        try {
            Claims claims = jwtFactory.parse(jwt);
            String type = claims.get(JwtFactory.CLAIM_TYPE, String.class);
            if (!JwtFactory.TYPE_ACCESS.equals(type)) {
                writeUnauthorized(res, "Token is not an access token");
                return;
            }

            AuthenticatedStaff principal = new AuthenticatedStaff(
                    Long.valueOf(claims.getSubject()),
                    claims.get(JwtFactory.CLAIM_EMPLOYEE_ID, String.class),
                    JwtFactory.roleFromClaims(claims),
                    claims.get(JwtFactory.CLAIM_SUBDIVISION_ID, Long.class),
                    claims.get(JwtFactory.CLAIM_DC_ID, Long.class),
                    Boolean.TRUE.equals(claims.get(JwtFactory.CLAIM_PASSWORD_RESET, Boolean.class))
            );

            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);

        } catch (JwtException ex) {
            log.debug("JWT rejected: {}", ex.getMessage());
            writeUnauthorized(res, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Unexpected JWT failure", ex);
            writeUnauthorized(res, "Invalid token");
        }
    }

    private void writeUnauthorized(HttpServletResponse res, String detail) throws IOException {
        SecurityContextHolder.clearContext();
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse err = ErrorResponse.of(ErrorCode.UNAUTHORIZED.name(), detail);
        objectMapper.writeValue(res.getOutputStream(), ApiResponse.error(err));
    }
}

