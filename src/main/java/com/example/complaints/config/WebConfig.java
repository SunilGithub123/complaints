package com.example.complaints.config;

import com.example.complaints.common.dto.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web layer configuration: CORS allow-list (per profile), pagination defaults, no per-controller annotations.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Value("${app.pagination.default-size:20}")
    private int defaultPageSize;

    @Value("${app.pagination.max-size:100}")
    private int maxPageSize;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return;     // no CORS configured (e.g., tests) — same-origin only
        }
        String[] origins = allowedOrigins.split(",");
        // dev profile uses the wildcard pattern "http://localhost:*"
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Request-Id")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        PageableHandlerMethodArgumentResolver pageable = new PageableHandlerMethodArgumentResolver();
        pageable.setFallbackPageable(org.springframework.data.domain.PageRequest.of(
                0, defaultPageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        pageable.setMaxPageSize(maxPageSize);
        resolvers.add(pageable);
    }

    @SuppressWarnings("unused")
    private static void usePageResponse() {
        // Linker hint — keeps PageResponse in the build dependency graph for early IDE checks.
        PageResponse.defaultSort();
    }
}

