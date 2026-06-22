package com.example.complaints.complaint.service;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.complaint.dto.SubmitComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintImageType;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import com.example.complaints.consumer.model.ConsumerMaster;
import com.example.complaints.consumer.repository.ConsumerMasterRepository;
import com.example.complaints.masterdata.model.ComplaintCategory;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.repository.ComplaintCategoryRepository;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import com.example.complaints.masterdata.repository.SubdivisionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the Stage-10b submit flow: real Postgres via Testcontainers, real
 * {@code LocalStorageService} writing into a temp directory, the full
 * {@link ComplaintCreationService} composition wired by Spring.
 *
 * <p>Stage-10b carry-over: closed here so the FE smoke (Stage 11) is not the first thing to
 * exercise this combination. One happy-path test per the minimum-test policy — concurrency
 * and validation paths are covered by their own unit / IT classes.</p>
 */
@SpringBootTest
@Testcontainers
class ComplaintCreationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complaints")
            .withUsername("complaints")
            .withPassword("complaints");

    // Created in a static initialiser so it's resolvable from @DynamicPropertySource,
    // which runs before any JUnit @TempDir injection.
    private static final Path STORAGE_ROOT;
    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("complaint-it-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temp storage root", e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.storage.type",       () -> "local");
        r.add("app.storage.local-path", STORAGE_ROOT::toString);
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        if (Files.exists(STORAGE_ROOT)) {
            try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Autowired ComplaintCreationService creation;
    @Autowired SubdivisionRepository subdivisions;
    @Autowired DistributionCenterRepository dcs;
    @Autowired ConsumerMasterRepository consumers;
    @Autowired ComplaintCategoryRepository categories;
    @Autowired ComplaintRepository complaints;
    @Autowired ComplaintHistoryRepository history;
    @Autowired ComplaintImageRepository images;

    private Long consumerMasterId;
    private Long distributionCenterId;
    private Long powerOutageCategoryId;

    @BeforeEach
    void seed() {
        // Wipe only the rows owned by this IT. We deliberately do NOT touch subdivision /
        // distribution_center / user_account — V1.2 seeds a bootstrap admin whose FKs into
        // subdivision break a blanket deleteAll(). Re-using master rows across runs is fine
        // because the upstream seed is idempotent and the codes are scoped to "-IT-".
        history.deleteAll();
        images.deleteAll();
        complaints.deleteAll();
        consumers.deleteAll();

        Subdivision sub = subdivisions.findByCode("SUB-IT-001").orElseGet(() ->
                subdivisions.save(Subdivision.builder()
                        .code("SUB-IT-001").name("IT Subdivision").district("Nashik").active(true).build()));
        DistributionCenter dc = dcs.findByCode("DC-IT-001").orElseGet(() ->
                dcs.save(DistributionCenter.builder()
                        .subdivisionId(sub.getId())
                        .code("DC-IT-001").name("IT DC").active(true).build()));
        ConsumerMaster consumer = consumers.save(ConsumerMaster.builder()
                .consumerId("MH00099999")
                .name("IT Consumer")
                .mobile("+919999900001")
                .distributionCenterId(dc.getId())
                .active(true)
                .build());

        ComplaintCategory cat = categories.findByCode("POWER_OUTAGE").orElseThrow();
        this.consumerMasterId = consumer.getId();
        this.distributionCenterId = dc.getId();
        this.powerOutageCategoryId = cat.getId();
    }

    @Test
    @DisplayName("submit with one image persists complaint+history+image and writes the file to disk")
    void submit_endToEnd_persistsEverything() throws IOException {
        VerifiedConsumer caller = new VerifiedConsumer("MH00099999", consumerMasterId, "+919999900001");
        SubmitComplaintRequest req = new SubmitComplaintRequest(
                "MH00099999", "+919999900001", powerOutageCategoryId,
                "Power outage in sector 7", "Plot 42");
        MockMultipartFile image = new MockMultipartFile(
                "images", "evidence.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        SubmitComplaintResponse response = creation.submit(caller, req, List.of(image));

        // ---------- API surface ----------
        assertThat(response.ticketNo()).matches("^MH\\d{6}\\d{8}$");
        assertThat(response.status()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(response.images()).hasSize(1);
        assertThat(response.images().get(0).sizeBytes()).isEqualTo(3);
        assertThat(response.images().get(0).url()).startsWith("file:");

        // ---------- DB rows ----------
        Complaint persisted = complaints.findByTicketNo(response.ticketNo()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(persisted.getDistributionCenterId()).isEqualTo(distributionCenterId);
        assertThat(persisted.getCategoryId()).isEqualTo(powerOutageCategoryId);
        assertThat(persisted.getContactMobile()).isEqualTo("+919999900001");
        // SLA: 24h post submission per V1.1 POWER_OUTAGE seed; allow a wide ±5min window.
        Instant expectedDeadline = persisted.getCreatedAt().plus(24, ChronoUnit.HOURS);
        assertThat(persisted.getSlaDeadline())
                .isBetween(expectedDeadline.minusSeconds(300), expectedDeadline.plusSeconds(300));

        List<ComplaintHistory> hist = history.findByComplaintIdOrderByChangedAtAsc(persisted.getId());
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).getFromStatus()).isNull();
        assertThat(hist.get(0).getToStatus()).isEqualTo(ComplaintStatus.SUBMITTED);

        List<ComplaintImage> imgRows = images.findByComplaintIdOrderByIdAsc(persisted.getId());
        assertThat(imgRows).hasSize(1);
        ComplaintImage row = imgRows.get(0);
        assertThat(row.getImageType()).isEqualTo(ComplaintImageType.COMPLAINT);
        assertThat(row.getContentType()).isEqualTo("image/jpeg");
        assertThat(row.getStorageKey()).matches("complaint/" + persisted.getId() + "/COMPLAINT/.*\\.jpg");

        // ---------- File on disk ----------
        Path onDisk = STORAGE_ROOT.resolve(row.getStorageKey());
        assertThat(Files.exists(onDisk)).as("image written by LocalStorageService").isTrue();
        assertThat(Files.readAllBytes(onDisk)).containsExactly(0xff, 0xd8, 0xff);
    }
}


