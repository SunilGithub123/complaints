package com.example.complaints.complaint.service;

import com.example.complaints.complaint.repository.ComplaintSequenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for {@link TicketNumberService} against a real Postgres. Verifies the two
 * properties Mockito cannot:
 * <ul>
 *   <li>the atomic upsert returns a strictly increasing, unique sequence number per call;</li>
 *   <li>concurrent callers serialise correctly under the advisory lock — no duplicates, no gaps.</li>
 * </ul>
 *
 * <p>Per the minimum-test policy: 1 happy + 1 contention. Exhaustive thread-safety matrices live
 * in production, not here.</p>
 */
@SpringBootTest
@Testcontainers
class TicketNumberServiceIT {

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
    private TicketNumberService ticketNumberService;

    @Autowired
    private ComplaintSequenceRepository sequenceRepository;

    @Test
    @DisplayName("sequential calls return strictly increasing ticket numbers")
    void sequentialCalls_areStrictlyIncreasing() {
        sequenceRepository.deleteAll();

        String first  = ticketNumberService.nextTicketNumber();
        String second = ticketNumberService.nextTicketNumber();
        String third  = ticketNumberService.nextTicketNumber();

        assertThat(first).endsWith("00000001");
        assertThat(second).endsWith("00000002");
        assertThat(third).endsWith("00000003");
        // Shared MH<yyyymm> prefix.
        assertThat(first.substring(0, 8)).isEqualTo(second.substring(0, 8));
    }

    @Test
    @DisplayName("concurrent callers receive unique, sequential ticket numbers under the advisory lock")
    void concurrentCallers_receiveUniqueNumbers() throws Exception {
        sequenceRepository.deleteAll();

        int parallelism = 16;
        int callsPerThread = 4;
        int total = parallelism * callsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Callable<String>> tasks = IntStream.range(0, total)
                    .<Callable<String>>mapToObj(i -> ticketNumberService::nextTicketNumber)
                    .toList();
            List<Future<String>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            Set<String> issued = new HashSet<>();
            for (Future<String> f : futures) {
                issued.add(f.get());
            }
            assertThat(issued).as("every ticket must be unique").hasSize(total);

            // Trailing 8-digit suffix should cover the full range 1..total (no gaps, no duplicates).
            Set<Integer> suffixes = issued.stream()
                    .map(t -> Integer.parseInt(t.substring(t.length() - 8)))
                    .collect(Collectors.toSet());
            assertThat(suffixes).hasSize(total);
            assertThat(suffixes).allMatch(n -> n >= 1 && n <= total);
        } finally {
            pool.shutdownNow();
        }
    }
}

