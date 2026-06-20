package com.example.complaints.auth.bootstrap;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.config.BootstrapAdminProperties;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the very first ADMIN user from {@code BOOTSTRAP_ADMIN_*} env vars on a fresh DB.
 *
 * <ol>
 *   <li>No-op if any active ADMIN already exists.</li>
 *   <li>Resolves the subdivision by {@code code}; warns and exits if not found.</li>
 *   <li>BCrypts the password and inserts the admin row with
 *       {@code enabled=true}, {@code password_reset_required=true}.</li>
 * </ol>
 *
 * See TECHNICAL_DESIGN.md 6 "Bootstrap Admin".
 */
@Component
@Order(100)     // runs after Flyway has migrated the schema
public class AuthBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);

    private final BootstrapAdminProperties props;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager em;

    public AuthBootstrapRunner(
            BootstrapAdminProperties props,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            EntityManager em) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.em = em;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.isComplete()) {
            log.warn("Bootstrap admin env vars not fully set "
                    + "(BOOTSTRAP_ADMIN_EMPLOYEE_ID / BOOTSTRAP_ADMIN_PASSWORD / BOOTSTRAP_ADMIN_SUBDIVISION_CODE). "
                    + "Skipping admin bootstrap.");
            return;
        }
        if (users.existsByRoleAndEnabledTrue(UserRole.ADMIN)) {
            log.info("Active ADMIN already exists — skipping bootstrap.");
            return;
        }
        if (users.existsByEmployeeId(props.employeeId())) {
            log.warn("Employee ID {} already exists; not overwriting.", props.employeeId());
            return;
        }

        Long subdivisionId = resolveSubdivisionIdByCode(props.subdivisionCode());
        if (subdivisionId == null) {
            log.warn("Subdivision code {} not found — cannot seed bootstrap admin.",
                    props.subdivisionCode());
            return;
        }

        UserAccount admin = UserAccount.builder()
                .employeeId(props.employeeId())
                .passwordHash(passwordEncoder.encode(props.password()))
                .passwordResetRequired(true)
                .role(UserRole.ADMIN)
                .fullName("Bootstrap Admin")
                .subdivisionId(subdivisionId)
                .distributionCenterId(null)
                .enabled(true)
                .notificationsPushEnabled(true)
                .build();
        users.save(admin);
        log.info("Seeded bootstrap ADMIN employee {} for subdivision {}",
                props.employeeId(), props.subdivisionCode());
    }

    private Long resolveSubdivisionIdByCode(String code) {
        // Native query avoids pulling Subdivision entity until the masterdata module lands.
        Object id = em.createNativeQuery("SELECT id FROM subdivision WHERE code = :code")
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .orElse(null);
        return id == null ? null : ((Number) id).longValue();
    }
}
