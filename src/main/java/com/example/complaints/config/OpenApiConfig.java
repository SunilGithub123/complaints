package com.example.complaints.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi metadata. See TECHNICAL_DESIGN.md §5.
 */
@Configuration
public class OpenApiConfig {

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
                ));
    }
}

