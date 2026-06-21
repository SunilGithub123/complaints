# Implementation Log — backend (`complaints`)

> Living record of what has actually shipped on the **backend** side, per phase / per
> stage. Update at the end of every stage. **Phases and stages here track [`ROADMAP.md`](ROADMAP.md).**
>
> **Frontend has its own log** at `complaints-frontend/docs/IMPLEMENTATION_LOG.md`.
> Stages that span both repos (e.g. Stage 3 OpenAPI contract export → orval codegen)
> appear in both logs; each log is the source of truth for its own slice and
> cross-links to the other.
>
> Format per entry:
> 1. **Scope delivered** — packages / files / endpoints / migrations.
> 2. **Incidents fixed during implementation** — root cause + fix, so we don't
>    relearn the lesson next phase.
> 3. **Tests added** (count + intent; per the minimum-test policy).
> 4. **Build status** at end of stage.
> 5. **Carry-overs / known follow-ups** — anything explicitly deferred.

---

## Phase 0 — Scaffolds (done before this log existed)

- **Backend** (`complaints/`): Maven single-module, Spring Boot 4.1, Java 21, IST timezone wiring, `ApiResponse` / `ErrorResponse` / `PageResponse`, `ErrorCode` enum + `BusinessException` + `GlobalExceptionHandler`, Caffeine cache, springdoc OpenAPI, security skeleton, Flyway with `V1.0__init_schema.sql` + `V1.1__seed_master_data.sql` + `V1.2__seed_bootstrap_admin.sql` placeholder, dev-only `V1000.0__seed_dev_data.sql`, ArchUnit `PackageBoundaryTest`, Testcontainers `ComplaintsApplicationIT` smoke.
- **Frontend scaffolds** are tracked in `complaints-frontend/docs/IMPLEMENTATION_LOG.md` (Phase 0 entry there).
- **Build status (backend)**: `./mvnw verify` green.

---

## Phase 1 — Staff Login + Master Data

### Stage 1 · Backend `auth` module — ✅ 2026-06-20

#### Scope delivered

**Domain** (`com.example.complaints.auth.model`)
- `UserRole` enum — `ADMIN`, `ENGINEER`, `TECHNICIAN`; `authority()` helper for Spring Security.
- `UserAccount` entity — Lombok `@Builder`, all `user_account` columns, `@PrePersist/@PreUpdate`.
- `RefreshToken` entity — token hash (SHA-256 of raw JWT), expiry, revoked flag.

**Persistence** (`auth.repository`)
- `UserAccountRepository` — `findByEmployeeId`, `existsByRoleAndEnabledTrue`, plus partial-unique-aware existence checks for the "one active admin per subdivision" / "one active engineer per DC" guardrails.
- `RefreshTokenRepository` — `findByTokenHash`, `@Modifying` bulk `revokeAllForUser`.

**Security** (`auth.security`)
- `JwtProperties` (record, `@ConfigurationProperties("jwt")`).
- `JwtFactory` — single signing entry-point, explicit per-purpose builders for access / refresh / consumer-verification tokens (HS256, JJWT 0.12).
- `AuthenticatedStaff` — `UserDetails` record, placed in `SecurityContext` after JWT auth.
- `JwtAuthFilter` — extracts `Bearer`, validates, pins principal; on failure writes clean 401 `ApiResponse` body.
- `PasswordResetRequiredFilter` — blocks every staff route except the small allow-list (`/change-password`, `/logout`, `/me`) while `passwordResetRequired = true`.

**DTOs** (`auth.dto`, all records)
- `LoginRequest`, `LoginResponse`, `RefreshTokenRequest`, `ChangePasswordRequest`, `StaffSummaryResponse`.

**Mapper**
- `auth.mapper.UserAccountMapper` — hand-written, no MapStruct (hard rule 3).

**Service** (`auth.service.StaffAuthService`)
- `login`, `refresh` (rotates refresh JWT — old revoked, new issued), `changePassword` (revokes all other sessions on success), `logout`, `me`.
- Refresh tokens persisted as SHA-256 hashes; raw JWT never hits the DB.

**Controller** (`auth.controller.StaffAuthController`)
- `POST /api/v1/staff/auth/login` (public)
- `POST /api/v1/staff/auth/refresh` (public)
- `POST /api/v1/staff/auth/change-password` (authenticated; allowed during reset-required)
- `POST /api/v1/staff/auth/logout` (authenticated)
- `GET  /api/v1/staff/me` (authenticated; allowed during reset-required)
- All annotated with springdoc `@Operation` / `@Tag` so FE codegen sees them.

**Wiring**
- `config.SecurityConfig` — filter chain: `JwtAuthFilter` → `PasswordResetRequiredFilter` → authz. Public allow-list for actuator/swagger/login/refresh; `/api/v1/staff/**` authenticated; `/api/v1/admin/**`, `/api/v1/engineer/**`, `/api/v1/technician/**` gated by `hasRole`.
- `auth.bootstrap.AuthBootstrapRunner` — now actually seeds the first admin from `BOOTSTRAP_ADMIN_*` env vars (no-op when an active admin exists; warns and exits on missing subdivision).
- `ComplaintsApplication` — `@ConfigurationPropertiesScan` now covers both `config` and `auth.security` packages.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `mvnw verify` IT failed: `SchemaManagementException: missing table [refresh_token]` | Spring Boot 4.1 **split the Flyway autoconfig** into its own starter. We had `flyway-core` on the classpath but no autoconfig module → Flyway never ran → Hibernate `validate` mode failed on first entity. | Replaced `flyway-core` declaration with **`spring-boot-starter-flyway`** in `pom.xml`. Kept `flyway-database-postgresql` for the Postgres provider. |
| 2 | `PackageBoundaryTest.services_must_not_call_other_modules_repositories` flagged 13 legitimate `auth.service → auth.repository` deps | The original rule used `com.example.complaints.(*)..service..` → `com.example.complaints.(*)..repository..`. ArchUnit's `(*)` **captures path segments but does not establish equality across the two captures**, so same-module dependencies were treated as cross-module. | Rewrote the rule using ArchUnit's **`SlicesRuleDefinition`**. Slices by `com.example.complaints.(*)..`, `notDependOnEachOther()`, and `ignoreDependency(any, NOT resideInAPackage("..repository.."))` so only cross-module repository deps fire. |
| 3 | `StaffAuthControllerTest` failed to load context: `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'` | Spring Boot 4.1 ships **Jackson 3** (`tools.jackson.databind.ObjectMapper`); the legacy Jackson-2 `ObjectMapper` bean is not autowired by default. Our filters imported the Jackson-2 type. | Switched `JwtAuthFilter`, `PasswordResetRequiredFilter`, and `StaffAuthControllerTest` to **`tools.jackson.databind.ObjectMapper`**. |
| 4 | Compile errors: `package org.springframework.boot.test.autoconfigure.web.servlet does not exist`, `cannot find symbol WebMvcTest` / `AutoConfigureMockMvc` / `SecurityAutoConfiguration` | Spring Boot 4.1 **moved test slice annotations** to module-specific packages. | New import paths:<br>• `@WebMvcTest` / `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.*`<br>• `SecurityAutoConfiguration` → `org.springframework.boot.security.autoconfigure.*` |
| 5 | After fix #4 the context still failed: `No qualifying bean of type 'JwtFactory'` for `JwtAuthFilter` | `@WebMvcTest` **still instantiates `Filter` beans** even with `addFilters = false`. `JwtAuthFilter` and `PasswordResetRequiredFilter` were being constructed in the slice context, needing `JwtFactory`. | Kept `@AutoConfigureMockMvc(addFilters = false)` (so filters don't sit in the chain) and added `@MockitoBean JwtFactory jwtFactory;` to satisfy filter construction. Comment in the test explains why. |
| 6 | `archunit.properties` had `archRule.failOnEmptyShould=false` from scaffold time | Was set because the per-module packages didn't yet exist, so rules matched zero classes. | (Left as-is — rules now match real classes; the flag has no effect when there are matches. Flip to `true` once we feel confident in all-stage matchings; tracked as a Phase-2 follow-up.) |

#### Tests added

Per minimum-test policy: 1 happy + 1 unhappy per behavior; no exhaustive matrices.

- `auth/service/StaffAuthServiceTest` — 2 tests:
  - happy login (returns token pair, staff summary, `passwordResetRequired`)
  - bad password → `BAD_CREDENTIALS` (same code as unknown user, no info leak)
- `auth/controller/StaffAuthControllerTest` (`@WebMvcTest`) — 2 tests:
  - happy MockMvc login (200, `success=true`, access-token in envelope)
  - bad-credentials propagation (401, `error.code=BAD_CREDENTIALS`)

(No new repository IT this stage — Testcontainers IT (`ComplaintsApplicationIT`) covers schema apply + bean wiring including the bootstrap runner; per minimum-test policy we don't add a `@DataJpaTest` for derived `findByEmployeeId` until it earns its keep.)

#### Build status

```
[INFO] Tests run: 11, Failures: 0, Errors: 0  (Surefire — unit)
[INFO] Tests run:  1, Failures: 0, Errors: 0  (Failsafe — IT; Flyway V1.0→1.1→1.2→1000.0 applies cleanly)
[INFO] BUILD SUCCESS
```

#### Carry-overs / known follow-ups

- **`archunit.properties` → `failOnEmptyShould=true`** — ✅ done in Stage 2.
- **Repository IT slice** — add a small `@DataJpaTest`-driven IT when the first non-derived query lands (Phase 2 specs).
- **OpenAPI security scheme** — declare `bearerAuth` in `OpenApiConfig` so Swagger UI surfaces an "Authorize" button. Defer until first protected endpoint is exercised manually.
- **CHANGE_PASSWORD audit** — listener will be added with the `audit` module (Phase 7 per ROADMAP). For now, `lastLoginAt` is updated on successful login.
- **Bootstrap admin row** — `fullName` is hard-coded to `"Bootstrap Admin"`; first thing the real admin will do is change password (forced) and edit profile (Phase 2 admin-user-mgmt).

---

### Stage 2 · Backend `masterdata` module — ✅ 2026-06-20

#### Scope delivered

**Domain** (`com.example.complaints.masterdata.model`)
- `Subdivision`, `DistributionCenter`, `ComplaintCategory` — Lombok `@Builder`, all schema columns, `@PrePersist/@PreUpdate` timestamps. Backed by the `subdivision`, `distribution_center`, `complaint_category` tables shipped in `V1.0__init_schema.sql`.

**Persistence** (`masterdata.repository`)
- `SubdivisionRepository`, `DistributionCenterRepository`, `ComplaintCategoryRepository` — derived finders only (`findByCode`, `existsByCode`, `findBySubdivisionId(Pageable)`); no `@Query` until a real specification is needed.

**DTOs** (`masterdata.dto`, all records)
- `SubdivisionRequest` / `Response`, `DistributionCenterRequest` / `Response`, `ComplaintCategoryRequest` / `Response`.
- Request DTOs carry `jakarta.validation` constraints (`@NotBlank`, `@Pattern` for code shape, `@Min/@Max` for SLA hours).

**Mappers** (hand-written, one per entity)
- `SubdivisionMapper`, `DistributionCenterMapper`, `ComplaintCategoryMapper` — all timestamps run through `DateUtils.toIst(...)` so the wire format is IST.

**Services** (`masterdata.service`)
- One class per aggregate (SRP, per copilot-instructions). Each exposes `list`, `get`, `create`, `update`, `setActive`, plus a cross-module `requireActive(...)` returning the entity for other services to consume.
- **Caching:** `@Cacheable` on `get(id)` against the Caffeine caches already declared in `CaffeineCacheConfig` (`subdivisions` / `distributionCenters` / `categories`); `@CacheEvict(allEntries=true)` on every write.
- **Scope enforcement (`DistributionCenterService`):** writes accept the `AuthenticatedStaff` principal and check `me.subdivisionId() == target.subdivisionId`; mismatch → `DC_NOT_IN_SCOPE`. Non-admin callers → `FORBIDDEN`.

**Controllers** (`masterdata.controller`)
- `MasterdataReadController` (`GET /api/v1/staff/masterdata/...`) — any authenticated staff can list/get subdivisions, DCs, categories. Supports `?subdivisionId=` filter on DCs.
- `SubdivisionAdminController`, `DistributionCenterAdminController`, `ComplaintCategoryAdminController` — mounted under `/api/v1/admin/masterdata/...` so the `SecurityConfig.hasRole("ADMIN")` matcher gates them. Each exposes `POST` create, `PUT /{id}` update, `POST /{id}/activate`, `POST /{id}/deactivate`.

**ErrorCodes added**
- `SUBDIVISION_CODE_TAKEN`, `DC_CODE_TAKEN`, `CATEGORY_CODE_TAKEN` (all `409 CONFLICT`) — follow the existing `EMPLOYEE_ID_TAKEN` pattern.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | Carry-over from Stage 1: `archunit.properties` had `archRule.failOnEmptyShould=false`. | Was set during Phase 0 when per-module packages didn't exist so rules matched zero classes. With auth + masterdata now in, an accidentally-empty rule should be a build failure. | Flipped to `archRule.failOnEmptyShould=true`. Comment in the file explains the history. All 5 rules still match real classes — re-ran `PackageBoundaryTest` to confirm. |

No new incidents originated in Stage 2 itself — the SB 4.1 / Jackson 3 / ArchUnit lessons from Stage 1 carried through cleanly. The masterdata controller test reused the **same WebMvcTest pattern** documented in Stage 1 incident #5 (mock the filter's `JwtFactory` dependency + `addFilters = false`).

#### Tests added

Minimum-test policy applied: 1 happy + 1 unhappy per **representative** behavior. CRUD on three aggregates is shape-identical, so we test the *create* path on each service (uniqueness + write) and one MockMvc surface as a controller-layer canary.

- `masterdata/service/SubdivisionServiceTest` — 2 tests (happy create, duplicate-code conflict).
- `masterdata/service/DistributionCenterServiceTest` — 2 tests (happy create in admin's own subdivision, cross-subdivision attempt → `DC_NOT_IN_SCOPE`).
- `masterdata/service/ComplaintCategoryServiceTest` — 2 tests (happy create, duplicate-code conflict).
- `masterdata/controller/SubdivisionAdminControllerTest` (`@WebMvcTest`) — 2 tests (happy `POST` 200 + envelope, validation failure 400 + `VALIDATION_FAILED`).

(Admin-vs-non-admin authorization on `/api/v1/admin/**` is enforced by `SecurityConfig` and verified end-to-end in the boot IT — no separate slice test, per the "don't mock the system under test" rule.)

#### Build status

```
[INFO] Tests run: 19, Failures: 0, Errors: 0  (Surefire — unit;  +8 from Stage 1)
[INFO] Tests run:  1, Failures: 0, Errors: 0  (Failsafe — IT; same Flyway baseline)
[INFO] BUILD SUCCESS
```

ArchUnit strict mode is on; all 5 boundary rules still pass.

#### Carry-overs / known follow-ups

- **Deactivation guardrails not yet enforced.** A subdivision with active DCs, a DC with an active engineer, or a category with open complaints can all be deactivated today. We don't yet have the complaint module to reference; will add the checks alongside the staff-user-mgmt service (Phase 2 admin features) and complaint module (Phase 3).
- **`sla_config` table** (per-category SLA override) is not exposed. `ComplaintCategory.slaHours` is the only SLA surface in v1; the override table stays unused until we ship configurable per-DC SLA.
- **Subdivision-write authority.** Any ADMIN can currently create / update / deactivate any subdivision (subdivision-scope check applies only to DCs). Per the BRD, subdivisions are DBA-seeded; tighter restriction (e.g. a `SYSADMIN` role, or moving subdivision writes to a DBA-only endpoint) is a Phase-7 admin-features item.
- **Audit + domain events** — every write should publish a `MasterdataChangedEvent` for the audit log. Deferred to the `audit` module (Phase 7).
- **`@DataJpaTest` repository IT** — still not added; the boot IT covers schema + bean wiring. Will revisit when a non-derived query lands (Phase 3 complaint specs).

---

### Stage 2.1 · DB-managed `created_at` / `updated_at` — ✅ 2026-06-20

#### Why

Stage 1 + Stage 2 entities each carried `@PrePersist` / `@PreUpdate` lifecycle callbacks that
duplicated `DEFAULT now()` in the schema. Two sources of truth for the same column = bugs
waiting (e.g. a JDBC-only writer or a raw `UPDATE` would skip the JPA hook). Moved the
responsibility to where it belongs: the DB.

#### Scope delivered

- **New Flyway migration `V1.3__add_updated_at_trigger.sql`** —
  - Creates a re-usable `set_updated_at()` plpgsql function (`NEW.updated_at = now()`).
  - A `DO $$` block auto-discovers every `public.*` table with an `updated_at` column and attaches a `BEFORE UPDATE … FOR EACH ROW EXECUTE FUNCTION set_updated_at()` trigger. Idempotent via `DROP TRIGGER IF EXISTS`.
  - Result on a fresh DB: 7 triggers — `subdivision`, `distribution_center`, `complaint_category`, `sla_config`, `consumer_master`, `user_account`, `complaint`.
- **Entities updated** (`auth.model.UserAccount`, `RefreshToken`; `masterdata.model.{Subdivision, DistributionCenter, ComplaintCategory}`):
  - Removed `@PrePersist` / `@PreUpdate` callbacks.
  - `created_at` column → `@Generated(event = EventType.INSERT)` + `insertable = false, updatable = false`. DB DEFAULT supplies the value on INSERT; Hibernate re-reads it.
  - `updated_at` column → `@Generated(event = {EventType.INSERT, EventType.UPDATE})` + `insertable = false, updatable = false`. DB DEFAULT + trigger; Hibernate re-reads after both events.
  - `RefreshToken.last_used_at` stays app-managed (it's a domain field, not an auto-bump).
- **Callers cleaned up** — removed `.createdAt(Instant.now()).updatedAt(Instant.now())` from `AuthBootstrapRunner.run(...)` and `StaffAuthService.issueTokenPair(...)`. Unused `import java.time.Instant` pruned where applicable.

#### Verification

Round-tripped against a fresh local Postgres (not just Hibernate validate):

```
INSERT … TRG_TEST …    →  created_at == updated_at                    ✓
UPDATE … 1 s later     →  updated_at > created_at; delta 1.012s       ✓
```

#### Tests added

None. The behaviour is verified by:
- The boot IT (`ComplaintsApplicationIT`) — Hibernate schema validation now passes against the trigger-equipped schema, and the bootstrap runner inserts the admin row without manually setting timestamps.
- Direct SQL round-trip above.

Adding a `@DataJpaTest`-style unit just to assert "INSERT sets `created_at`" would be testing Postgres, not our code.

#### Incidents

None. Hibernate's `@Generated` integration worked first-try with `insertable=false, updatable=false`; the trigger function attached to all 7 target tables; all 20 existing tests stayed green (19 unit + 1 IT).

#### Build status

```
[INFO] Tests run: 19, Failures: 0, Errors: 0  (Surefire — unit)
[INFO] Tests run:  1, Failures: 0, Errors: 0  (Failsafe — IT; Flyway now applies V1.0→1.1→1.2→1.3→1000.0)
[INFO] BUILD SUCCESS
```

#### Carry-overs / known follow-ups

- **New tables added in future migrations** that include `updated_at` should either re-run the same `DO $$` block, or attach the trigger explicitly inside their own `CREATE TABLE` migration. Document this in the migration template (Phase 7 follow-up).
- **Optimistic locking** — `@Version` columns are still not present. When concurrent updates become real (Phase 3 complaint state machine), add `version BIGINT NOT NULL DEFAULT 0` columns and the matching `@Version` field, distinct from the time-based `updated_at`.

---

### Stage 3 · OpenAPI contract export (backend half) — ✅ 2026-06-20

> Stage 3 spans **both** repos. This is the backend half — shipping a
> reproducible OpenAPI snapshot that the FE codegen consumes. The frontend
> half (orval pipeline, custom `fetch` transport, generated TanStack Query
> hooks) lives in `complaints-frontend/docs/IMPLEMENTATION_LOG.md` and is
> the source of truth for that work.

#### Scope delivered

- **`OpenApiConfig` gained Bearer auth schemes.** Declared two HTTP-bearer security schemes that the FE codegen (and Swagger UI's "Authorize" button) understand:
  - `bearerAuth` — staff access JWT (default global requirement, applied to `/staff/**`, `/admin/**`, `/engineer/**`, `/technician/**`)
  - `consumerVerifyToken` — 5-min consumer verification JWT (to be opted in per-endpoint by `/consumer/**` controllers when Phase 5 lands)
- **`OpenApiExportIT` (new Failsafe test, `src/test/java/com/example/complaints/openapi/`).** Boots the app on a random port via Testcontainers + Postgres, GETs `/v3/api-docs`, writes the response to **`docs/openapi.json`** (project root relative).
  - Reproducible contract snapshot — FE `pnpm api:gen` reads the committed file; no running backend needed.
  - Uses plain `java.net.http.HttpClient` (Java 21 try-with-resources) — `TestRestTemplate` was repackaged in SB 4.1 and we'd rather not chase its module.
- **`docs/openapi.json` checked in** — OpenAPI 3.0.1, 23 paths covering Stage 1 (`/staff/auth/*`, `/staff/me`) + Stage 2 (`/staff/masterdata/*`, `/admin/masterdata/*`), with both security schemes wired up. Cleared the Stage 1 follow-up "OpenAPI security scheme".

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `import org.springframework.boot.test.web.client.TestRestTemplate` would not resolve. | SB 4.1 repackaged or moved `TestRestTemplate`; the dependency that exposes it is no longer transitive via the slice deps we already pull in, and we don't want to add another starter just for this single use. | Skipped `TestRestTemplate` entirely. The IT uses `java.net.http.HttpClient` against `@LocalServerPort` (which itself moved to `org.springframework.boot.test.web.server.LocalServerPort` in SB 4.1 — noted for future ITs). |

#### Tests added

- `openapi/OpenApiExportIT` — 1 IT. Both an assertion (`200`, body contains `"openapi"`) **and** a build-artifact producer (writes `docs/openapi.json`). Running `./mvnw verify` keeps the snapshot in sync with the live spec; CI will fail if the spec stops being valid JSON or the server stops booting. No unit tests — there is no business logic to mock; the value is in the live spec round-trip.

#### Build status

```
[INFO] Tests run: 19, Failures: 0, Errors: 0  (Surefire — unit)
[INFO] Tests run:  2, Failures: 0, Errors: 0  (Failsafe — IT; ComplaintsApplicationIT + OpenApiExportIT)
[INFO] BUILD SUCCESS
docs/openapi.json — 17 KB, 23 paths, schemes: [bearerAuth, consumerVerifyToken]
```

> **FE half status (cross-referenced from `complaints-frontend/docs/IMPLEMENTATION_LOG.md`):** ✅ landed same day. `packages/api` orval pipeline + `customFetch` transport + 2 Vitest cases green; `apps/web` build 61.18 KB gzipped (budget 180 KB).

#### Carry-overs / known follow-ups

- **Per-endpoint security overrides.** Once `/consumer/**` controllers land (Phase 5), they must override the global `bearerAuth` requirement with `@SecurityRequirement(name = "consumerVerifyToken")` at the controller / method level so the FE generates the correct typed clients.
- **Spec drift CI guard.** Today the IT *writes* `docs/openapi.json`. A future CI step should also **diff** against the committed copy and fail the build on uncommitted spec changes (forces "regenerate FE bindings before merging" hygiene). Track for Phase 7.
- **Snapshot sync to FE repo.** Today this is a manual `cp ../complaints/docs/openapi.json packages/api/openapi.json`. Automate alongside the spec-drift guard above.

---

### Stage 4 · Backend support for frontend feature slice — ✅ 2026-06-21 (no backend changes required)

> Stage 4 was **frontend-led** (staff login + force-change-password + master-data
> read screens in `apps/web`). Source of truth for the FE-side scope, incidents,
> tests, and build status is
> [`complaints-frontend/docs/IMPLEMENTATION_LOG.md`](../../complaints-frontend/docs/IMPLEMENTATION_LOG.md)
> (Stage 4 entry).

#### Backend impact

- **Zero code changes on the backend.** The auth + masterdata APIs shipped in
  Stages 1–2 covered every screen the FE built (`/staff/auth/login`,
  `/staff/auth/refresh`, `/staff/auth/change-password`, `/staff/auth/logout`,
  `/staff/me`, `/staff/masterdata/{subdivisions,distribution-centers,categories}`).
- **No new BE incidents.** The 401 → refresh-once contract added in Stage 1
  worked unchanged with the FE's `customFetch` transport; the
  `passwordResetRequired` allow-list (`/change-password`, `/logout`, `/me`)
  enforced by `PasswordResetRequiredFilter` was mirrored exactly by the FE's
  `RequirePasswordChanged` guard — confirmed by the manual smoke loop documented
  in the FE log.

#### FE half summary (cross-referenced)

- Tailwind v4 (`@tailwindcss/vite`) + hand-authored shadcn-style primitives (only the 8 actually used); skipped `dropdown-menu`.
- Zustand auth store + boot-time `setAuthHooks(...)` wiring; `auth:logout` listener clears + navigates.
- React Router 7 with three guard layers (`RequireAuth` → `RequirePasswordChanged` → `DashboardLayout`); `RequireRole` exported for Phase 2.
- Screens: `LoginScreen`, `ChangePasswordScreen`, `DashboardLayout`, `Home`, `SubdivisionsScreen`, `DistributionCentersScreen`, `CategoriesScreen`, `NotFound`. `MasterdataTable<TRow>` extracted **after the third** masterdata screen (per the "second-time abstraction" rule).
- i18n: `@complaints/i18n` now ships an `i18next` singleton with full EN + MR catalogues; MR mirrors EN key tree exactly.
- 4 Vitest + RTL cases pass (Login happy/unhappy, RequirePasswordChanged on/off).
- Bundle: now sits with **~50 KB of headroom** against the 180 KB gzipped budget.

#### Carry-overs that touch backend (or BE+FE jointly)

These are tracked here so the BE side doesn't lose sight of them; full FE-side
list lives in the FE log.

- **`useMe` proactive call at boot is deferred (Phase 2).** Until then, the FE hydrates `staff` from `localStorage` and only revalidates on the next 401. If we ever change the staff-summary shape, FE clients will keep a stale `staff` object across refreshes until a protected call fails. **Backend implication:** keep `StaffSummaryResponse` strictly additive on v1; any field removal is a breaking change for cached FE state. Track alongside the existing Phase 7 "spec drift CI guard" follow-up.
- **Marathi parity CI guard (Phase 7).** Today the EN/MR key trees match by code review only. When CI lands, the same job should also assert backend `ErrorCode` strings have an `mr` translation in `@complaints/i18n` for any code surfaced to consumers.
- **`PasswordResetRequiredFilter` allow-list is FE-mirrored.** Any change to the allow-list (`/change-password`, `/logout`, `/me`) is now a coordinated BE+FE change. Document in TECHNICAL_DESIGN §5 once we touch it again.

---

## Phase 2 — Admin write screens + staff user management — ☐ not started

---

## How to update this log

1. At the end of a stage, append (or fill in) the corresponding subsection.
2. Keep entries terse. **What shipped**, **what bit us**, **what we tested**, **what we deferred**.
3. Don't rewrite history — additive only. If we have to undo something, add a new entry that says so.
4. Cross-reference TECHNICAL_DESIGN / BRD section numbers where relevant, so a reader can jump to the design context.

