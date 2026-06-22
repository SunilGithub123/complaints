package com.example.complaints.auth.security;

import com.example.complaints.auth.service.OtpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConsumerVerificationFilterTest {

    private JwtFactory jwt;
    private ConsumerVerificationFilter filter;

    @BeforeEach
    void setUp() {
        var props = new JwtProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5),
                "complaints-api",
                "test-secret-must-be-at-least-32-bytes-long-_-_-_");
        jwt = new JwtFactory(props);
        // OtpProperties not needed by the filter; reference here to mirror prod wiring shape.
        new OtpProperties(6, Duration.ofMinutes(5), 5, 30, 5);
        filter = new ConsumerVerificationFilter(jwt, new ObjectMapper());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("valid consumer token pins VerifiedConsumer and lets the chain proceed")
    void validToken_pinsPrincipal() throws Exception {
        var issued = jwt.issueConsumerVerificationToken("MH00010001", 42L, "+919900000001");
        var req = new MockHttpServletRequest("POST", "/api/v1/consumer/complaints");
        req.addHeader("Authorization", "Bearer " + issued.jwt());
        var res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // Capture the principal during chain.doFilter (filter clears the context after).
        var captured = new VerifiedConsumer[1];
        org.mockito.Mockito.doAnswer(inv -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            captured[0] = (VerifiedConsumer) auth.getPrincipal();
            return null;
        }).when(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].consumerId()).isEqualTo("MH00010001");
        assertThat(captured[0].consumerMasterId()).isEqualTo(42L);
        assertThat(captured[0].mobile()).isEqualTo("+919900000001");
    }

    @Test
    @DisplayName("missing Authorization header returns 401 CONSUMER_VERIFICATION_REQUIRED")
    void missingHeader_returns401() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/consumer/complaints");
        var res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("CONSUMER_VERIFICATION_REQUIRED");
        verify(chain, never()).doFilter(any(), any());
    }
}

