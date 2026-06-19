-- =====================================================================
-- Complaint Resolution System — PostgreSQL Schema (v1)
-- Target: PostgreSQL 14+
-- Managed via Flyway. Split into versioned files under:
--   src/main/resources/db/migration/
--     V1.0__init_schema.sql       (all tables + indexes below)
--     V1.1__seed_master_data.sql  (the seed inserts at the bottom)
--     V1.2__init_update.sql       (any follow-up additive changes)
--     V1.3__<description>.sql     ... and so on
-- Naming convention: V<major>.<minor>__<snake_case_description>.sql
-- Never edit an already-applied V<x.y> file; add a new V<x.y+1> instead.
-- =====================================================================

-- ---------- MASTER DATA ----------

CREATE TABLE station (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  UNIQUE NOT NULL,
    name        VARCHAR(200) NOT NULL,
    district    VARCHAR(100),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE substation (
    id          BIGSERIAL PRIMARY KEY,
    station_id  BIGINT       NOT NULL REFERENCES station(id),
    code        VARCHAR(50)  UNIQUE NOT NULL,
    name        VARCHAR(200) NOT NULL,
    address     TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_substation_station ON substation(station_id);

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

-- ---------- CONSUMERS ----------

CREATE TABLE consumer (
    id            BIGSERIAL PRIMARY KEY,
    consumer_id   VARCHAR(50) UNIQUE NOT NULL,    -- from external EB system
    name          VARCHAR(200),
    mobile        VARCHAR(15) NOT NULL,
    email         VARCHAR(200),
    address       TEXT,
    substation_id BIGINT      REFERENCES substation(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consumer_mobile     ON consumer(mobile);
CREATE INDEX idx_consumer_substation ON consumer(substation_id);

-- ---------- USERS / AUTH ----------

CREATE TABLE user_account (
    id                     BIGSERIAL PRIMARY KEY,
    username               VARCHAR(100) UNIQUE NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    role                   VARCHAR(30)  NOT NULL,       -- CONSUMER, ENGINEER, FIELD_TECHNICIAN, ADMIN
    consumer_id            BIGINT       REFERENCES consumer(id),  -- only if role = CONSUMER
    full_name              VARCHAR(200),
    email                  VARCHAR(200),
    mobile                 VARCHAR(15),
    substation_id          BIGINT       REFERENCES substation(id), -- for staff scope
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    notifications_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_role     ON user_account(role);
CREATE INDEX idx_user_consumer ON user_account(consumer_id);

CREATE TABLE staff_registration_request (
    id              BIGSERIAL PRIMARY KEY,
    full_name       VARCHAR(200) NOT NULL,
    email           VARCHAR(200) NOT NULL,
    mobile          VARCHAR(15)  NOT NULL,
    requested_role  VARCHAR(30)  NOT NULL,    -- ENGINEER, FIELD_TECHNICIAN, ADMIN
    substation_id   BIGINT       REFERENCES substation(id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by     BIGINT       REFERENCES user_account(id),
    reviewed_at     TIMESTAMPTZ,
    reason          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_staff_req_status ON staff_registration_request(status);

CREATE TABLE otp (
    id          BIGSERIAL PRIMARY KEY,
    mobile      VARCHAR(15) NOT NULL,
    otp_hash    VARCHAR(255) NOT NULL,
    purpose     VARCHAR(30)  NOT NULL,    -- LOGIN, REGISTER, GUEST_COMPLAINT, PASSWORD_RESET
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed    BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_mobile_purpose ON otp(mobile, purpose);

CREATE TABLE refresh_token (
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

-- ---------- COMPLAINTS ----------

CREATE TABLE complaint (
    id                       BIGSERIAL PRIMARY KEY,
    ticket_no                VARCHAR(30) UNIQUE NOT NULL,
    consumer_id              BIGINT      NOT NULL REFERENCES consumer(id),
    created_by_user_id       BIGINT      REFERENCES user_account(id),  -- null if guest
    is_guest                 BOOLEAN     NOT NULL DEFAULT FALSE,
    guest_mobile             VARCHAR(15),
    category_id              BIGINT      NOT NULL REFERENCES complaint_category(id),
    severity                 VARCHAR(20) NOT NULL,   -- LOW, MEDIUM, HIGH, CRITICAL
    description              TEXT        NOT NULL,
    location                 TEXT,
    substation_id            BIGINT      REFERENCES substation(id),
    status                   VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
        -- SUBMITTED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED,
        -- CANCELLED, REJECTED, DUPLICATE
    assigned_engineer_id     BIGINT      REFERENCES user_account(id),
    assigned_technician_id   BIGINT      REFERENCES user_account(id),
    parent_complaint_id      BIGINT      REFERENCES complaint(id),  -- if DUPLICATE
    sla_deadline             TIMESTAMPTZ NOT NULL,
    sla_breached             BOOLEAN     NOT NULL DEFAULT FALSE,
    resolution_notes         TEXT,
    sla_breach_reason        TEXT,
    cancellation_reason      TEXT,
    rejection_reason         TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at              TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ
);
CREATE INDEX idx_complaint_status              ON complaint(status);
CREATE INDEX idx_complaint_consumer            ON complaint(consumer_id);
CREATE INDEX idx_complaint_engineer            ON complaint(assigned_engineer_id);
CREATE INDEX idx_complaint_technician          ON complaint(assigned_technician_id);
CREATE INDEX idx_complaint_substation_status   ON complaint(substation_id, status);
CREATE INDEX idx_complaint_sla_open            ON complaint(sla_deadline)
    WHERE sla_breached = FALSE
      AND status NOT IN ('RESOLVED','CLOSED','CANCELLED','REJECTED','DUPLICATE');
CREATE INDEX idx_complaint_created_at          ON complaint(created_at);

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
    target_type    VARCHAR(50),     -- COMPLAINT, USER, STATION, SUBSTATION, CATEGORY, SLA
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

