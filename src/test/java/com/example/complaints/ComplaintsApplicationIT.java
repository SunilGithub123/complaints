package com.example.complaints;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boot smoke + schema verification. This is suffixed {@code IT} so it runs under Failsafe
 * (not Surefire) since it needs Docker.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>the Spring context loads cleanly,</li>
 *   <li>Flyway applies all V1.x migrations on a fresh Postgres,</li>
 *   <li>{@code BootstrapAdminProperties} binds without env vars (warns and continues).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class ComplaintsApplicationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complaints")
            .withUsername("complaints")
            .withPassword("complaints");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoadsAndSchemaApplies() {
        // If Flyway migrations or any @Configuration fails, the test fails.
    }
}

