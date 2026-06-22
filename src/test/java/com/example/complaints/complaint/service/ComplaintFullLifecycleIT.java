package com.example.complaints.complaint.service;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintSeverity;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Phase 4 lifecycle: consumer submits → engineer assigns → technician starts
 * → technician resolves → engineer closes. One test covers the whole happy path; per-method
 * edge cases are covered in the corresponding unit tests.
 *
 * <p>Carry-over from Stage 13 (intentionally deferred from that stage's test policy): we
 * wanted the resolution + closure services in scope so the lifecycle could run end-to-end
 * without mocking the second half of the flow.</p>
 */
@SpringBootTest
@Testcontainers
class ComplaintFullLifecycleIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complaints")
            .withUsername("complaints")
            .withPassword("complaints");

    private static final Path STORAGE_ROOT;
    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("complaint-lifecycle-it-");
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
    @Autowired ComplaintAssignmentService assignment;
    @Autowired ComplaintResolutionService resolution;
    @Autowired ComplaintClosureService closure;

    @Autowired SubdivisionRepository subdivisions;
    @Autowired DistributionCenterRepository dcs;
    @Autowired ConsumerMasterRepository consumers;
    @Autowired ComplaintCategoryRepository categories;
    @Autowired ComplaintRepository complaints;
    @Autowired ComplaintHistoryRepository history;
    @Autowired ComplaintImageRepository images;
    @Autowired UserAccountRepository users;

    private Long consumerMasterId;
    private Long distributionCenterId;
    private Long subdivisionId;
    private Long powerOutageCategoryId;
    private UserAccount engineer;
    private UserAccount technician;

    @BeforeEach
    void seed() {
        // Owned by THIS IT only — see ComplaintCreationIT for why we don't deleteAll() on
        // subdivision / distribution_center / user_account.
        history.deleteAll();
        images.deleteAll();
        complaints.deleteAll();
        consumers.deleteAll();

        Subdivision sub = subdivisions.findByCode("SUB-LC-001").orElseGet(() ->
                subdivisions.save(Subdivision.builder()
                        .code("SUB-LC-001").name("Lifecycle Subdiv").district("Nashik").active(true).build()));
        DistributionCenter dc = dcs.findByCode("DC-LC-001").orElseGet(() ->
                dcs.save(DistributionCenter.builder()
                        .subdivisionId(sub.getId())
                        .code("DC-LC-001").name("Lifecycle DC").active(true).build()));
        ConsumerMaster consumer = consumers.save(ConsumerMaster.builder()
                .consumerId("MH00088888").name("LC Consumer").mobile("+919999900002")
                .distributionCenterId(dc.getId()).active(true).build());

        engineer = users.findByEmployeeId("ENG-LC-001").orElseGet(() ->
                users.save(staff("ENG-LC-001", UserRole.ENGINEER, sub.getId(), dc.getId())));
        technician = users.findByEmployeeId("TECH-LC-001").orElseGet(() ->
                users.save(staff("TECH-LC-001", UserRole.TECHNICIAN, sub.getId(), dc.getId())));

        ComplaintCategory cat = categories.findByCode("POWER_OUTAGE").orElseThrow();
        this.consumerMasterId = consumer.getId();
        this.distributionCenterId = dc.getId();
        this.subdivisionId = sub.getId();
        this.powerOutageCategoryId = cat.getId();
    }

    @Test
    @DisplayName("happy lifecycle: SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED")
    void happyLifecycle() {
        // ---------- 1. Consumer submits ----------
        VerifiedConsumer consumerCaller =
                new VerifiedConsumer("MH00088888", consumerMasterId, "+919999900002");
        SubmitComplaintResponse submitted = creation.submit(consumerCaller,
                new SubmitComplaintRequest("MH00088888", "+919999900002",
                        powerOutageCategoryId, "Whole street is dark", "Sector 7"),
                List.of());
        Long complaintId = submitted.complaintId();
        assertThat(submitted.status()).isEqualTo(ComplaintStatus.SUBMITTED);

        // ---------- 2. Engineer assigns ----------
        AuthenticatedStaff engineerCaller = staffPrincipal(engineer);
        assignment.assign(engineerCaller, complaintId,
                new AssignComplaintRequest(technician.getId(), ComplaintSeverity.HIGH));
        assertStatus(complaintId, ComplaintStatus.ASSIGNED);

        // ---------- 3. Technician starts ----------
        AuthenticatedStaff techCaller = staffPrincipal(technician);
        resolution.start(techCaller, complaintId);
        assertStatus(complaintId, ComplaintStatus.IN_PROGRESS);

        // ---------- 4. Technician resolves (on-time → no breach reason needed) ----------
        resolution.resolve(techCaller, complaintId,
                new ResolveComplaintRequest("Replaced blown transformer fuse", null));
        Complaint resolved = complaints.findById(complaintId).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.isSlaBreached()).isFalse();

        // ---------- 5. Engineer closes ----------
        closure.close(engineerCaller, complaintId, new CloseComplaintRequest(null));
        Complaint closed = complaints.findById(complaintId).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getAssignedTechnicianId()).isEqualTo(technician.getId());
        assertThat(closed.getAssignedEngineerId()).isEqualTo(engineer.getId());
        assertThat(closed.getSeverity()).isEqualTo(ComplaintSeverity.HIGH);

        // ---------- 6. Audit trail: 5 rows in chronological order ----------
        List<ComplaintHistory> rows = history.findByComplaintIdOrderByChangedAtAsc(complaintId);
        assertThat(rows).extracting(ComplaintHistory::getToStatus).containsExactly(
                ComplaintStatus.SUBMITTED,
                ComplaintStatus.ASSIGNED,
                ComplaintStatus.IN_PROGRESS,
                ComplaintStatus.RESOLVED,
                ComplaintStatus.CLOSED
        );
        assertThat(rows.get(0).getFromStatus()).isNull();
        assertThat(rows.get(1).getFromStatus()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(rows.get(4).getFromStatus()).isEqualTo(ComplaintStatus.RESOLVED);
    }

    private UserAccount staff(String employeeId, UserRole role, Long sub, Long dc) {
        return UserAccount.builder()
                .employeeId(employeeId)
                .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTU") // dummy bcrypt
                .passwordResetRequired(false)
                .role(role)
                .fullName(employeeId)
                .subdivisionId(sub)
                .distributionCenterId(role == UserRole.ADMIN ? null : dc)
                .enabled(true)
                .notificationsPushEnabled(false)
                .build();
    }

    private AuthenticatedStaff staffPrincipal(UserAccount u) {
        return new AuthenticatedStaff(u.getId(), u.getEmployeeId(), u.getRole(),
                u.getSubdivisionId(), u.getDistributionCenterId(), false);
    }

    private void assertStatus(Long id, ComplaintStatus expected) {
        assertThat(complaints.findById(id).orElseThrow().getStatus()).isEqualTo(expected);
    }
}

