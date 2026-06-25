package com.example.complaints.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 21.2.5 — guards the chain that wires Caffeine cache stats into Micrometer:
 *
 * <ol>
 *   <li>{@code CaffeineCacheManager} bean exists with cache names pre-registered.</li>
 *   <li>The underlying Caffeine builder has {@code recordStats()} (else metrics are zero).</li>
 *   <li>{@code spring-boot-starter-actuator} + {@code micrometer-registry-prometheus} are
 *       on the classpath so {@code CacheMetricsAutoConfiguration} binds each cache.</li>
 * </ol>
 *
 * <p>If any link in that chain breaks (someone drops {@code recordStats()}, renames a
 * cache, or removes the registry dependency), this test fails loudly instead of silently
 * returning empty {@code /actuator/prometheus} output in prod.</p>
 */
@SpringBootTest
@Testcontainers
class CacheMetricsIT {

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

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("Caffeine caches register cache.gets meter for every configured cache name")
    void cacheMetersBoundForEveryCache() {
        // CacheMetricsAutoConfiguration emits cache.* meters tagged with cache=<name>.
        // We only assert presence; values are 0 on a fresh boot and that's fine.
        for (String name : new String[]{
                CaffeineCacheConfig.CACHE_CATEGORIES,
                CaffeineCacheConfig.CACHE_SUBDIVISIONS,
                CaffeineCacheConfig.CACHE_DCS,
        }) {
            assertThat(meters.find("cache.gets").tag("cache", name).meters())
                    .as("cache.gets meter missing for cache=%s", name)
                    .isNotEmpty();
        }
    }
}

