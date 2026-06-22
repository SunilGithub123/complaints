-- =====================================================================
-- V1.4__add_complaint_version.sql
-- Adds an optimistic-lock version column to `complaint`. Phase 4 introduces
-- concurrent updates (engineer assigns while admin reassigns; technician
-- resolves while engineer marks duplicate; ...), and JPA @Version is the
-- cleanest way to surface stale-state writes as 409 COMPLAINT_VERSION_CONFLICT
-- rather than silent overwrites.
--
-- Distinct from the time-based `updated_at` column (V1.3 trigger): `version`
-- is a strict monotonic counter, incremented by Hibernate on every flush,
-- and is what the optimistic-lock predicate compares against.
-- =====================================================================

ALTER TABLE complaint
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

