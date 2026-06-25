-- Stage 21.2.8 — perf indexes derived from EXPLAIN ANALYZE on 50k complaints
-- + 20k device tokens (audit in IMPLEMENTATION_LOG.md, evidence in
-- docs/DB_INDEX_AUDIT.md). Both indexes flip Seq Scan → Index Scan on
-- queries that fire on every category-deactivate and once per night.
--
-- NOTE: regular CREATE INDEX (not CONCURRENTLY) because Flyway wraps each
-- migration in a transaction and CREATE INDEX CONCURRENTLY cannot run inside
-- one. Both indexes are on currently-tiny tables (complaint, device_token);
-- the brief AccessExclusiveLock at deploy time is acceptable for the v1
-- single-instance topology. If we ever go multi-node, run these manually
-- with CONCURRENTLY first and add no-op `DO $$ BEGIN PERFORM 1; END $$;`
-- statements here.

-- Q1 carry — `existsByCategoryIdAndStatusIn(categoryId, statuses)` plus the
-- category-filtered branch of the complaint Specification search. Without
-- this, every category deactivate attempt does a Seq Scan over the whole
-- complaint table. The (category_id, status) composite covers both the
-- existence check (any-of-N statuses) and the search filter (single status).
CREATE INDEX ix_complaint_category_status ON complaint (category_id, status);

-- Q8 carry — `DeviceTokenRepository.markInactiveOlderThan(cutoff)`. Nightly
-- sweep WHERE active = true AND updated_at < ?. Partial index pays for
-- itself: only the active subset is even maintained, and that's exactly
-- the set the sweep cares about. Inactive rows skip the index entirely.
CREATE INDEX ix_device_token_active_updated
    ON device_token (updated_at)
    WHERE active = true;

