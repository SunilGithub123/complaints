package com.example.complaints.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Stage 3 / 21.2.4 — OpenAPI snapshot export <b>and</b> spec-drift guard.
 *
 * <p>Boots the app on a random port, fetches {@code /v3/api-docs}, and either:</p>
 *
 * <ul>
 *   <li><b>Verify mode (default)</b> — compares against the committed
 *       {@code docs/openapi.json}. If they differ, the test fails with a one-shot
 *       hint pointing at how to regenerate. <b>This is what CI runs.</b></li>
 *   <li><b>Update mode</b> — pass {@code -Dopenapi.update=true} to overwrite
 *       {@code docs/openapi.json} with the freshly generated spec. Use this whenever
 *       you intentionally changed a controller, DTO, or {@code @Operation}.</li>
 * </ul>
 *
 * <p><b>Workflow:</b></p>
 * <pre>
 *   # I added / changed an endpoint:
 *   ./mvnw verify -Dopenapi.update=true     # regenerates docs/openapi.json
 *   git add docs/openapi.json                # commit alongside the controller change
 *
 *   # CI on the resulting PR:
 *   ./mvnw verify                            # verify-mode; passes iff snapshot matches
 * </pre>
 *
 * <p>The snapshot is the reproducible contract export consumed by the frontend
 * {@code packages/api} orval codegen — frontend builds do not need a running
 * backend; they read the committed snapshot. Stable {@code operationId}s
 * (Stage 21.2.1) make the diff signal-to-noise high enough for this guard to be
 * useful.</p>
 *
 * <p>String equality is good enough today because springdoc emits deterministic
 * output: same controllers / same DTOs / same {@code @Operation} → byte-identical
 * spec across runs. We have 21 stages of evidence for this. If that ever changes
 * (e.g. a springdoc upgrade introduces non-determinism), the comparison can grow a
 * canonicalisation step then, not speculatively now.</p>
 *
 * <p>Runs under Failsafe (suffix {@code IT}) because it needs Docker for
 * Postgres via Testcontainers.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiExportIT {

    private static final String UPDATE_FLAG = "openapi.update";

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

    @LocalServerPort
    int port;

    @Test
    void exportOrVerifyOpenApiSnapshot() throws Exception {
        String live = fetchLiveSpec();

        Path target = Paths.get("").toAbsolutePath().resolve("docs").resolve("openapi.json");

        if (Boolean.parseBoolean(System.getProperty(UPDATE_FLAG, "false"))) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, live);
            return; // explicit update mode — never fails
        }

        // Verify mode (default + CI).
        if (!Files.exists(target)) {
            fail("docs/openapi.json is missing. Generate it with:\n\n  ./mvnw verify -D" + UPDATE_FLAG + "=true\n");
        }
        String committed = Files.readString(target);
        if (!live.equals(committed)) {
            fail("OpenAPI snapshot drift detected — committed docs/openapi.json does not match the live spec.\n\n"
                    + "First divergence:\n" + firstDiff(committed, live) + "\n"
                    + "Regenerate with:\n  ./mvnw verify -D" + UPDATE_FLAG + "=true\n"
                    + "then commit docs/openapi.json alongside your controller change.");
        }
    }

    private String fetchLiveSpec() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotBlank().contains("\"openapi\"");
        return response.body();
    }

    /**
     * Cheap diff summary. springdoc emits the spec on one line, so we report the first
     * differing character offset plus a window of surrounding text rather than line-based
     * context (which would not exist).
     */
    private static String firstDiff(String expected, String actual) {
        int min = Math.min(expected.length(), actual.length());
        int i = 0;
        while (i < min && expected.charAt(i) == actual.charAt(i)) {
            i++;
        }
        if (i == min && expected.length() == actual.length()) {
            return "(strings differ but no character mismatch found — likely encoding)";
        }
        int from = Math.max(0, i - 60);
        int toExp = Math.min(expected.length(), i + 60);
        int toAct = Math.min(actual.length(), i + 60);
        return String.format(
                "  at offset %d (expected length %d, live length %d)%n"
              + "    committed ...%s...%n"
              + "    live      ...%s...%n",
                i, expected.length(), actual.length(),
                expected.substring(from, toExp).replace("\n", "\\n"),
                actual.substring(from, toAct).replace("\n", "\\n"));
    }

    @SuppressWarnings("unused")
    private static List<String> unusedHintForFutureCanonicaliser() {
        // Kept as a doc hook: if springdoc ever goes non-deterministic, the
        // pre-comparison normaliser lives here.
        return List.of();
    }
}
