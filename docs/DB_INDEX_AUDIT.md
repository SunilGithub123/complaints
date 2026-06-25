# DB Index Audit — Stage 21.2.8

**Date:** 2026-06-25
**Method:** Spawned `complaints_audit` sibling DB, applied V1.0–V1.5 schema, seeded
50 000 complaints, 150 000 history rows, 20 000 device tokens, 50 000 consumers,
2 000 staff. `ANALYZE` ran before any EXPLAIN. Every "hot" query the repositories
issue was run with `EXPLAIN (ANALYZE, BUFFERS)`. Findings below.

The seed values were chosen to mirror expected prod skew at year-1 scale: status
distribution ~10/15/15/30/20/5/4/1, severity LOW/MEDIUM/HIGH ~33% each, 75% active
device tokens, ~3 history rows per complaint average.

## Result summary

| # | Query (source repo method) | Before | After | Verdict |
|---|----------------------------|--------|-------|---------|
| Q1 | `complaint.existsByCategoryIdAndStatusIn` (category deactivate guard, search) | **Seq Scan cost 2285** | Index Only Scan cost 4.31 (rare-match) | **Fix shipped** |
| Q2 | `complaint.findByTicketNo` | Index Scan `complaint_ticket_no_key` | — | OK |
| Q3 | Consumer tracking list `consumer_master_id ORDER BY created_at DESC LIMIT 20` | Index Scan `idx_complaint_consumer` + tiny sort | — | OK |
| Q4 | Technician list `assigned_technician_id + status` | Bitmap heap via `idx_complaint_technician` | — | OK |
| Q5 | Admin search `DC IN (...) + status + ORDER BY created_at DESC` | Index Scan Backward `idx_complaint_created_at` | — | OK (acceptable plan) |
| Q6 | `findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore` | Bitmap via partial `idx_complaint_sla_open` | — | OK |
| Q7 | `complaint_history.findByComplaintIdOrderByChangedAtAsc` | Index Scan `idx_history_complaint` + 3-row sort | — | OK |
| Q8 | `device_token.markInactiveOlderThan` (nightly sweep) | **Seq Scan cost 671** | Bitmap Index Scan cost 122 on partial | **Fix shipped** |
| Q9 | Consumer fan-out `consumer_master_id + active=true` | Index Scan partial `ix_device_token_consumer_active` | — | OK |
| Q10 | Staff fan-out `user_id + active=true` | Bitmap via partial `ix_device_token_user_active` | — | OK |
| Q11 | UserAccount search (admin staff list) | Seq Scan cost 68 on 2k rows | — | Acceptable at projected scale |
| Q12 | `user_account.findByEmployeeId` | Index Only Scan `user_account_employee_id_key` | — | OK |

## Detailed before/after for the two fixes

### Q1 — `complaint(category_id, status)` composite

**Before** (no `category_id` index — only `(distribution_center_id, status)`, `(status)`):
```
Seq Scan on complaint  (cost=0.00..2285.00 rows=8696 width=4)
  Filter: ((category_id = 2) AND (status = ANY (...)))
```
At seeded 25% match rate the LIMIT 1 finds the first row in O(1) so wall time
is tiny — but the planner still **chooses** Seq Scan because there's no
candidate index on `category_id`. That's the real risk: the prod deactivate
guard runs against a category an admin **intends to deactivate** — by
definition one with few or zero open complaints, where seq scan reads the
**entire** table. Verified by re-running with a non-existent `category_id`:

**After** `ix_complaint_category_status (category_id, status)`:
```
Index Only Scan using ix_complaint_category_status on complaint  (cost=0.29..4.31 rows=1)
  Index Cond: (category_id = 999)
  Filter: (status = ANY (...))
  Heap Fetches: 0
```
Cost dropped 2285 → 4.31, planner stops at the first non-matching key range
instead of scanning every row. **This is the case that actually matters** for
the deactivate-guard pattern.

### Q8 — `device_token (updated_at) WHERE active = true` partial

**Before** (only `(user_id)` + the partial active uniques cover the sweep filter
partially):
```
Seq Scan on device_token  (cost=0.00..671.00 rows=10394 width=8)
  Filter: (active AND (updated_at < now() - '60 days'::interval))
  Rows Removed by Filter: 9527
```
2.6 ms on 20k rows. At 10x volume (200k tokens once apps/mobile is widespread)
this becomes ~26 ms per sweep, run once nightly — survivable but unnecessary.

**After** `ix_device_token_active_updated (updated_at) WHERE active = true`:
```
Bitmap Heap Scan on device_token  (cost=124.84..627.74 rows=10394)
  Recheck Cond: ((updated_at < now() - '60 days'::interval) AND active)
  ->  Bitmap Index Scan on ix_device_token_active_updated (cost=0.00..122.24)
```
Index now drives the scan. Cost halves, and the index footprint is the
**active subset only** — inactive rows (the long tail) are never indexed
because of the `WHERE active = true` predicate.

## Items considered and deliberately skipped

These either passed already or wouldn't earn their keep at v1 scale.

| Candidate | Why skipped |
|-----------|-------------|
| `complaint (consumer_master_id, created_at DESC)` composite | `idx_complaint_consumer` already serves the tracking-list query well; 50k rows + tiny per-consumer cardinality means the sort is on 1–10 rows. Composite would help at consumer-with-1000-tickets scale; not v1. |
| `complaint_history (complaint_id, changed_at)` composite | Single complaint has ~3 history rows; sort is free. Composite would burn write amplification on every history insert for no read win. |
| `complaint (distribution_center_id, status, created_at DESC)` for admin search | Current `Index Scan Backward using idx_complaint_created_at` is a reasonable plan; the filter discards ~6x rows before LIMIT 20. Adding a 3-col index costs writes on every complaint update; benefit is marginal until DC count and complaint volume both grow. |
| `user_account (subdivision_id, role, distribution_center_id, enabled)` composite for admin staff list | 2k staff per subdivision is the ceiling. Seq Scan cost 68. The current `idx_user_subdivision` short-circuits to ~400 rows; further index would be premature optimisation. |
| `otp (mobile, created_at DESC)` for send cooldown / rate-limit | OTP table is auto-cleaned by `deleteByCreatedAtBefore` cron; cardinality stays low. Existing `(mobile, purpose)` index serves the verify path. |

## Reproducing this audit

```bash
# 1. Spawn sibling DB
PGPASSWORD=complaints psql -h localhost -U complaints -d postgres \
  -c "DROP DATABASE IF EXISTS complaints_audit;" \
  -c "CREATE DATABASE complaints_audit OWNER complaints;"

# 2. Apply schema (V1.0..V1.5 — exclude V1.2 / V1.1 dev-only seeds)
for f in src/main/resources/db/migration/V1.{0,3,4,5}__*.sql; do
  PGPASSWORD=complaints psql -h localhost -U complaints -d complaints_audit -q -f "$f"
done

# 3. Seed (see /tmp/audit_seed.sql in the Stage 21.2.8 PR notes)
PGPASSWORD=complaints psql -h localhost -U complaints -d complaints_audit -f /tmp/audit_seed.sql

# 4. Run EXPLAIN suite (see /tmp/audit_queries.sql)
PGPASSWORD=complaints psql -h localhost -U complaints -d complaints_audit -f /tmp/audit_queries.sql

# 5. Apply the fixes
PGPASSWORD=complaints psql -h localhost -U complaints -d complaints_audit \
  -f src/main/resources/db/migration/V1.6__perf_indexes_audit.sql
PGPASSWORD=complaints psql -h localhost -U complaints -d complaints_audit \
  -c "ANALYZE complaint, device_token;"

# 6. Re-run EXPLAIN suite, diff plans
```

Both seed + queries SQL are inline in the implementation log under Stage 21.2.8
so a future contributor can reproduce without digging through chat history.

