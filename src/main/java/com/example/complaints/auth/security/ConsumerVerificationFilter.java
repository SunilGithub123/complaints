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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Gates {@code /api/v1/consumer/**} on a valid consumer verification JWT
 * (TECHNICAL_DESIGN.md §5.1 + §6). Distinct from {@link JwtAuthFilter} because:
 * <ul>
 *   <li>It only accepts {@code typ=consumer} tokens; staff access tokens are rejected.</li>
 *   <li>The principal is a {@link VerifiedConsumer}, not an {@code AuthenticatedStaff}.</li>
 *   <li>A missing / invalid token → {@code 401 CONSUMER_VERIFICATION_REQUIRED} (consumers
 *       cannot "refresh" — they re-run the OTP cycle).</li>
 * </ul>
 *
 * <p>Skips {@code /api/v1/auth/consumer/**} (OTP send / verify themselves) and any non-consumer
 * route so this filter never interferes with staff or public endpoints.</p>
 */
@Component
public class ConsumerVerificationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConsumerVerificationFilter.class);
    private static final String BEARER = "Bearer ";
    private static final String CONSUMER_PATH_PREFIX = "/api/v1/consumer/";

    private final JwtFactory jwtFactory;
    private final ObjectMapper objectMapper;

    public ConsumerVerificationFilter(JwtFactory jwtFactory, ObjectMapper objectMapper) {
        this.jwtFactory = jwtFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CONSUMER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            writeUnauthorized(res, "Missing consumer verification token");
            return;
        }

        String jwt = header.substring(BEARER.length()).trim();
        try {
            Claims claims = jwtFactory.parse(jwt);
            String type = claims.get(JwtFactory.CLAIM_TYPE, String.class);
            if (!JwtFactory.TYPE_CONSUMER.equals(type)) {
                writeUnauthorized(res, "Token is not a consumer verification token");
                return;
            }

            VerifiedConsumer principal = new VerifiedConsumer(
                    claims.getSubject(),
                    claims.get(JwtFactory.CLAIM_CONSUMER_MASTER_ID, Long.class),
                    claims.get(JwtFactory.CLAIM_CONSUMER_MOBILE, String.class)
            );

            // Pin as an authentication so @AuthenticationPrincipal resolution works. No granted
            // authorities — authorization on /consumer/** is by-token, not role-based.
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, AuthorityUtils.NO_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                chain.doFilter(req, res);
            } finally {
                // Don't leak the principal to whatever thread the servlet container hands the
                // socket back to next — same hygiene SecurityContextHolderFilter normally does.
                var current = SecurityContextHolder.getContext().getAuthentication();
                if (current == auth || current instanceof AnonymousAuthenticationToken) {
                    SecurityContextHolder.clearContext();
                }
            }

        } catch (JwtException ex) {
            log.debug("Consumer JWT rejected: {}", ex.getMessage());
            writeUnauthorized(res, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Unexpected consumer-JWT failure", ex);
            writeUnauthorized(res, "Invalid consumer verification token");
        }
    }

    private void writeUnauthorized(HttpServletResponse res, String detail) throws IOException {
        SecurityContextHolder.clearContext();
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse err = ErrorResponse.of(
                ErrorCode.CONSUMER_VERIFICATION_REQUIRED.name(), detail);
        objectMapper.writeValue(res.getOutputStream(), ApiResponse.error(err));
    }
}

