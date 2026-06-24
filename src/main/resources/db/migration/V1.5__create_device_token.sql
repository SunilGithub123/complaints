-- Stage 21.1 — device token registry for push notifications.
-- Contract: docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md v1.0 (FROZEN 2026-06-25), §7.
--
-- V1.0 shipped a placeholder device_token table (staff-only, no device_id, no active flag,
-- no XOR principal binding). It has zero callers and zero data. Per hard-rule #5 we cannot
-- edit V1.0 — so this migration DROPs the placeholder and recreates the table in the
-- contract-conformant shape. Safe because the table has never been written to.
--
-- A device-token row is bound to *exactly one* principal — either a consumer
-- (consumer_master_id) or a staff user (user_id), enforced by ck_device_token__principal_xor.
-- The same physical device_id can hold one consumer row AND one staff row (shared phone in
-- a household / field engineer using same device for own complaints) — partial unique
-- indexes are on (principal, device_id) WHERE active, not on device_id alone.

DROP TABLE IF EXISTS device_token CASCADE;

CREATE TABLE device_token (
    id                  BIGSERIAL PRIMARY KEY,
    consumer_master_id  BIGINT NULL REFERENCES consumer_master(id),
    user_id             BIGINT NULL REFERENCES user_account(id),
    device_id           VARCHAR(64) NOT NULL,
    platform            VARCHAR(16) NOT NULL,   -- ANDROID | IOS | WEB
    push_token          TEXT NOT NULL,
    app_version         VARCHAR(32) NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_device_token__principal_xor CHECK (
        (consumer_master_id IS NOT NULL) <> (user_id IS NOT NULL)
    ),
    CONSTRAINT ck_device_token__platform CHECK (platform IN ('ANDROID', 'IOS', 'WEB'))
);

-- One active row per (consumer, device).
CREATE UNIQUE INDEX ux_device_token_consumer_device_active
    ON device_token (consumer_master_id, device_id)
    WHERE active AND consumer_master_id IS NOT NULL;

-- One active row per (staff user, device).
CREATE UNIQUE INDEX ux_device_token_user_device_active
    ON device_token (user_id, device_id)
    WHERE active AND user_id IS NOT NULL;

-- Fan-out lookups by principal for the Stage 21.2 listeners.
CREATE INDEX ix_device_token_consumer_active
    ON device_token (consumer_master_id) WHERE active AND consumer_master_id IS NOT NULL;
CREATE INDEX ix_device_token_user_active
    ON device_token (user_id) WHERE active AND user_id IS NOT NULL;

-- Attach the V1.3 set_updated_at() trigger explicitly (per Stage 2.1 carry-over: new
-- tables in later migrations attach the trigger themselves rather than re-running V1.3's
-- discovery block, so the migration is self-contained).
DROP TRIGGER IF EXISTS trg_device_token_updated_at ON device_token;
CREATE TRIGGER trg_device_token_updated_at
    BEFORE UPDATE ON device_token
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

