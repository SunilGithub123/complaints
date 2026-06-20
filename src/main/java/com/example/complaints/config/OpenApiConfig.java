package com.example.complaints.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi metadata. See TECHNICAL_DESIGN.md §5.
 *
 * <p>Declares two security schemes so the FE codegen (orval) and Swagger UI
 * understand how to authenticate:
 * <ul>
 *   <li>{@code bearerAuth} — staff JWT issued by /staff/auth/login (default for /staff/**, /admin/**, /engineer/**, /technician/**)</li>
 *   <li>{@code consumerVerifyToken} — 5-min consumer verification JWT (for /consumer/**)</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    private static final String STAFF_BEARER = "bearerAuth";
    private static final String CONSUMER_BEARER = "consumerVerifyToken";

    @Bean
    public OpenAPI complaintsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Complaint Resolution System API")
                        .version("v1")
                        .description("Backend API for the Maharashtra State Electricity Board Complaint Resolution System.")
                        .contact(new Contact().name("Engineering").email("eng@example.in"))
                        .license(new License().name("Internal — All Rights Reserved")))
                .servers(List.of(
                        new Server().url("/").description("Current host")
                ))
                .components(new Components()
                        .addSecuritySchemes(STAFF_BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Staff access token issued by POST /api/v1/staff/auth/login"))
                        .addSecuritySchemes(CONSUMER_BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Consumer verification token (5-min TTL, non-refreshable)")))
                .addSecurityItem(new SecurityRequirement().addList(STAFF_BEARER));
    }
}

