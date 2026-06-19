-- =====================================================================
-- Complaint Resolution System — PostgreSQL Schema (v1)
-- Target: PostgreSQL 14+
-- Managed via Flyway. Split into versioned files under:
--   src/main/resources/db/migration/
--     V1.0__init_schema.sql           (all tables + indexes below)
--     V1.1__seed_master_data.sql      (the seed inserts at the bottom)
--     V1.2__seed_bootstrap_admin.sql  (initial admin from BOOTSTRAP_ADMIN_* env vars)
--     V1.3__init_update.sql           (any follow-up additive changes)
--     V1.<n>__<description>.sql       ... and so on
-- Naming convention: V<major>.<minor>__<snake_case_description>.sql
-- Never edit an already-applied V<x.y> file; add a new V<x.y+1> instead.
-- =====================================================================
--
-- BUSINESS HIERARCHY (Maharashtra State Electricity Board)
-- ─────────────────────────────────────────────────────────
--   Subdivision Office (taluka level)   ←  Admin (Deputy Executive Engineer)
--        └── Distribution Center        ←  Engineer
--                 └── Technicians
--
-- (Offices above Subdivision are out of scope for v1.)
-- =====================================================================

-- ---------- MASTER DATA (hierarchy) ----------

CREATE TABLE subdivision (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  UNIQUE NOT NULL,   -- e.g. SUB-NSK-001
    name        VARCHAR(200) NOT NULL,          -- e.g. "Nashik Rural Subdivision"
    district    VARCHAR(100),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE distribution_center (
    id              BIGSERIAL PRIMARY KEY,
    subdivision_id  BIGINT       NOT NULL REFERENCES subdivision(id),
    code            VARCHAR(50)  UNIQUE NOT NULL,   -- e.g. DC-NSK-007
    name            VARCHAR(200) NOT NULL,          -- e.g. "Sinnar Distribution Center"
    address         TEXT,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_dc_subdivision ON distribution_center(subdivision_id);

CREATE TABLE complaint_category (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  UNIQUE NOT NULL,   -- POWER_OUTAGE, LOW_VOLTAGE, TRANSFORMER_FAULT, OTHER
    name        VARCHAR(200) NOT NULL,
    sla_hours   INT          NOT NULL DEFAULT 24,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sla_config (
    id           BIGSERIAL PRIMARY KEY,
    category_id  BIGINT      UNIQUE REFERENCES complaint_category(id),
    sla_hours    INT         NOT NULL,
    updated_by   BIGINT,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- CONSUMER MASTER (read-only from EB system) ----------
-- Loaded via the datasync module. Source of truth for "does this consumer exist".

CREATE TABLE consumer_master (
    id                      BIGSERIAL PRIMARY KEY,
    consumer_id             VARCHAR(50) UNIQUE NOT NULL,    -- external EB consumer number
    name                    VARCHAR(200),
    mobile                  VARCHAR(15) NOT NULL,           -- on-file mobile from EB
    email                   VARCHAR(200),
    address                 TEXT,
    distribution_center_id  BIGINT      REFERENCES distribution_center(id),
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    last_synced_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consumer_master_mobile ON consumer_master(mobile);
CREATE INDEX idx_consumer_master_dc     ON consumer_master(distribution_center_id);

-- ---------- USERS / AUTH (STAFF ONLY) ----------
-- This table holds ONLY staff (Admin / Engineer / Technician).
-- Consumers are NOT stored here in v1 — they have no account, no password,
-- no login. Consumers are verified per-action via Consumer ID + Mobile + OTP
-- (see `otp` table, purpose = 'CONSUMER_VERIFY') and identified at runtime
-- via a short-lived consumer verification JWT.
--
--   role = ADMIN       ↔ subdivision_id mandatory; distribution_center_id NULL (subdivision-wide)
--                        Seeded directly in DB (bootstrap runner on first boot, or DBA SQL insert).
--                        EXACTLY ONE active admin per subdivision.
--   role = ENGINEER    ↔ subdivision_id + distribution_center_id BOTH mandatory
--                        Created by an Admin only.
--                        EXACTLY ONE active engineer per DC.
--   role = TECHNICIAN  ↔ subdivision_id + distribution_center_id BOTH mandatory
--                        Created by an Admin or by an Engineer (engineer scoped to own DC).
--                        Many technicians per DC.

CREATE TABLE user_account (
    id                       BIGSERIAL PRIMARY KEY,
    employee_id              VARCHAR(50)  UNIQUE NOT NULL,             -- login identifier
    password_hash            VARCHAR(255) NOT NULL,                    -- BCrypt
    password_reset_required  BOOLEAN      NOT NULL DEFAULT TRUE,       -- forces password change on first login
    role                     VARCHAR(30)  NOT NULL,
    full_name                VARCHAR(200) NOT NULL,
    email                    VARCHAR(200),                             -- contact only, NOT used for login
    mobile                   VARCHAR(15),                              -- contact only
    subdivision_id           BIGINT       REFERENCES subdivision(id),
    distribution_center_id   BIGINT       REFERENCES distribution_center(id),
    created_by_user_id       BIGINT       REFERENCES user_account(id), -- admin/engineer who created this account (NULL for bootstrap admin)
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    notifications_push_enabled BOOLEAN    NOT NULL DEFAULT TRUE,       -- push opt-out; in-app entries always recorded
    last_login_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_role
        CHECK (role IN ('ADMIN','ENGINEER','TECHNICIAN')),
    CONSTRAINT chk_staff_scope
        CHECK (
            (role = 'ADMIN'    AND subdivision_id IS NOT NULL AND distribution_center_id IS NULL)
         OR (role IN ('ENGINEER','TECHNICIAN')
             AND subdivision_id IS NOT NULL
             AND distribution_center_id IS NOT NULL)
        )
);
CREATE INDEX idx_user_role         ON user_account(role);
CREATE INDEX idx_user_dc           ON user_account(distribution_center_id);
CREATE INDEX idx_user_subdivision  ON user_account(subdivision_id);

-- Enforce: exactly ONE active admin per subdivision.
CREATE UNIQUE INDEX uq_one_active_admin_per_subdivision
    ON user_account(subdivision_id)
    WHERE role = 'ADMIN' AND enabled = TRUE;

-- Enforce: exactly ONE active engineer per distribution center.
CREATE UNIQUE INDEX uq_one_active_engineer_per_dc
    ON user_account(distribution_center_id)
    WHERE role = 'ENGINEER' AND enabled = TRUE;

-- NOTE: There is no `staff_registration_request` table in v1.
-- Staff onboarding is direct creation by an authorized actor:
--   Admin     → seeded in DB (bootstrap runner / DBA insert)
--   Engineer  → created by an Admin
--   Technician → created by an Admin or by an Engineer (within engineer's DC)
-- Every newly-created staff row carries password_reset_required = TRUE.

CREATE TABLE otp (
    id          BIGSERIAL PRIMARY KEY,
    mobile      VARCHAR(15) NOT NULL,
    otp_hash    VARCHAR(255) NOT NULL,
    purpose     VARCHAR(30)  NOT NULL,    -- CONSUMER_VERIFY (consumer per-action verification), STAFF_PASSWORD_RESET (reserved for v2)
    consumer_id VARCHAR(50),              -- set when purpose = CONSUMER_VERIFY (the Consumer ID the OTP was sent for)
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed    BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_otp_purpose CHECK (purpose IN ('CONSUMER_VERIFY','STAFF_PASSWORD_RESET'))
);
CREATE INDEX idx_otp_mobile_purpose ON otp(mobile, purpose);
CREATE INDEX idx_otp_consumer       ON otp(consumer_id) WHERE consumer_id IS NOT NULL;

CREATE TABLE refresh_token (
    -- Staff refresh tokens only — consumers have no refresh token (the consumer
    -- verification JWT is short-lived, non-refreshable, not persisted).
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    token_hash    VARCHAR(255) UNIQUE NOT NULL,   -- SHA-256 of refresh JWT
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_user    ON refresh_token(user_id);
CREATE INDEX idx_refresh_token_expires ON refresh_token(expires_at)
    WHERE revoked = FALSE;

-- Per-month ticket sequence (used to build ticket_no = MH<YYYY><MM><8-digit-seq>).
-- Sequence resets each month → next_value starts at 1 every month.
CREATE TABLE complaint_sequence (
    year_month   CHAR(6)  PRIMARY KEY,            -- e.g. '202606'
    next_value   BIGINT   NOT NULL DEFAULT 1
);

-- ---------- COMPLAINTS ----------

CREATE TABLE complaint (
    id                          BIGSERIAL PRIMARY KEY,
    ticket_no                   VARCHAR(20) UNIQUE NOT NULL,        -- MH<YYYY><MM><8-digit-seq>, e.g. MH2026060000123
    consumer_master_id          BIGINT      NOT NULL REFERENCES consumer_master(id),
    contact_mobile              VARCHAR(15) NOT NULL,                     -- the OTP-verified mobile used at submission; mandatory; need NOT match consumer_master.mobile
    category_id                 BIGINT      NOT NULL REFERENCES complaint_category(id),
    severity                    VARCHAR(20),                              -- LOW, MEDIUM, HIGH; NULL at submission; set by engineer
    description                 TEXT        NOT NULL,
    location                    TEXT,
    distribution_center_id      BIGINT      REFERENCES distribution_center(id),  -- derived from consumer_master at submission; may be re-pointed by an Admin reassign
    status                      VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
        -- SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED
        -- Terminal alt: CANCELLED, REJECTED, DUPLICATE
        -- No RE-OPENED in v1 (consumer raises a new complaint instead).
    assigned_engineer_id        BIGINT      REFERENCES user_account(id),
    assigned_technician_id      BIGINT      REFERENCES user_account(id),
    parent_complaint_id         BIGINT      REFERENCES complaint(id),    -- if DUPLICATE
    sla_deadline                TIMESTAMPTZ NOT NULL,
    sla_breached                BOOLEAN     NOT NULL DEFAULT FALSE,
    resolution_notes            TEXT,
    sla_breach_reason           TEXT,
    cancellation_reason         TEXT,
    rejection_reason            TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at                 TIMESTAMPTZ,
    closed_at                   TIMESTAMPTZ,
    CONSTRAINT chk_severity CHECK (severity IS NULL OR severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT chk_status   CHECK (status IN (
        'SUBMITTED','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED',
        'CANCELLED','REJECTED','DUPLICATE'))
);
CREATE INDEX idx_complaint_status         ON complaint(status);
CREATE INDEX idx_complaint_consumer       ON complaint(consumer_master_id);
CREATE INDEX idx_complaint_engineer       ON complaint(assigned_engineer_id);
CREATE INDEX idx_complaint_technician     ON complaint(assigned_technician_id);
CREATE INDEX idx_complaint_dc_status      ON complaint(distribution_center_id, status);
CREATE INDEX idx_complaint_sla_open       ON complaint(sla_deadline)
    WHERE sla_breached = FALSE
      AND status NOT IN ('RESOLVED','CLOSED','CANCELLED','REJECTED','DUPLICATE');
CREATE INDEX idx_complaint_created_at     ON complaint(created_at);

CREATE TABLE complaint_history (
    id                 BIGSERIAL PRIMARY KEY,
    complaint_id       BIGINT      NOT NULL REFERENCES complaint(id) ON DELETE CASCADE,
    from_status        VARCHAR(30),
    to_status          VARCHAR(30) NOT NULL,
    changed_by_user_id BIGINT      REFERENCES user_account(id),
    note               TEXT,
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_complaint ON complaint_history(complaint_id);

CREATE TABLE complaint_image (
    id                   BIGSERIAL PRIMARY KEY,
    complaint_id         BIGINT      NOT NULL REFERENCES complaint(id) ON DELETE CASCADE,
    image_type           VARCHAR(20) NOT NULL DEFAULT 'COMPLAINT',  -- COMPLAINT (by consumer) | RESOLUTION (by technician)
    storage_key          TEXT        NOT NULL,
    size_bytes           INT         NOT NULL,
    content_type         VARCHAR(50),
    uploaded_by_user_id  BIGINT      REFERENCES user_account(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_image_type CHECK (image_type IN ('COMPLAINT','RESOLUTION'))
);
CREATE INDEX idx_image_complaint      ON complaint_image(complaint_id);
CREATE INDEX idx_image_complaint_type ON complaint_image(complaint_id, image_type);

CREATE TABLE feedback (
    id            BIGSERIAL PRIMARY KEY,
    complaint_id  BIGINT      UNIQUE NOT NULL REFERENCES complaint(id) ON DELETE CASCADE,
    rating        INT         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- NOTIFICATIONS ----------

CREATE TABLE notification (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    type          VARCHAR(50) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    body          TEXT,
    complaint_id  BIGINT      REFERENCES complaint(id) ON DELETE SET NULL,
    is_read       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user_unread ON notification(user_id, is_read, created_at DESC);

CREATE TABLE device_token (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    token         TEXT        UNIQUE NOT NULL,
    platform      VARCHAR(20) NOT NULL,    -- IOS, ANDROID
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_token_user ON device_token(user_id);

-- ---------- AUDIT ----------

CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    actor_user_id  BIGINT      REFERENCES user_account(id),
    action         VARCHAR(100) NOT NULL,
    target_type    VARCHAR(50),     -- COMPLAINT, USER, SUBDIVISION, DISTRIBUTION_CENTER, CATEGORY, SLA, DATA_SYNC
    target_id      BIGINT,
    details        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor   ON audit_log(actor_user_id);
CREATE INDEX idx_audit_target  ON audit_log(target_type, target_id);
CREATE INDEX idx_audit_created ON audit_log(created_at);

-- =====================================================================
-- Seed data (Flyway V1.1__seed_master_data.sql)
-- =====================================================================
INSERT INTO complaint_category (code, name, sla_hours) VALUES
    ('POWER_OUTAGE',      'Power Outage',       24),
    ('LOW_VOLTAGE',       'Low Voltage',        24),
    ('TRANSFORMER_FAULT', 'Transformer Fault',  24),
    ('OTHER',             'Other',              48)
ON CONFLICT (code) DO NOTHING;

-- =====================================================================
-- Bootstrap admin (Flyway V1.2__seed_bootstrap_admin.sql)
-- =====================================================================
-- The first ADMIN is inserted on app boot by a CommandLineRunner reading
--   BOOTSTRAP_ADMIN_EMPLOYEE_ID
--   BOOTSTRAP_ADMIN_PASSWORD            (hashed via BCrypt at runtime)
--   BOOTSTRAP_ADMIN_SUBDIVISION_CODE    (must exist in `subdivision`)
-- from env vars. The runner is a no-op once an ADMIN already exists.
-- The bootstrap admin row is created with password_reset_required = TRUE
-- so the first login forces an immediate password change.
-- This keeps the password hash out of source control and migration files.
