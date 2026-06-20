-- =====================================================================
-- V1.1__seed_master_data.sql
-- Seed the four default complaint categories with their default SLA hours.
-- Idempotent — safe to re-run via Flyway repair if needed.
-- =====================================================================

INSERT INTO complaint_category (code, name, sla_hours) VALUES
    ('POWER_OUTAGE',      'Power Outage',       24),
    ('LOW_VOLTAGE',       'Low Voltage',        24),
    ('TRANSFORMER_FAULT', 'Transformer Fault',  24),
    ('OTHER',             'Other',              48)
ON CONFLICT (code) DO NOTHING;

