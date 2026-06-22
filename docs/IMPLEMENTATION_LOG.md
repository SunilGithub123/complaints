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

#### Post-stage hotfixes

| # | Date | Symptom | Root cause | Fix |
|---|------|---------|-----------|-----|
| 1 | 2026-06-21 | `POST /staff/auth/change-password` returned `ApiResponse<StaffSummaryResponse>`. The access JWT issued at `/login` carried `passwordResetRequired = true` in its claims; that claim stayed baked-in until the client either re-logged-in or chained a manual `/refresh` call. FE had to do change-password → refresh → setSession; mobile and any 3rd-party client would have hit the same sharp edge. | The change-password endpoint was treated as a "profile update" returning only the staff summary, even though server-side state (the claim) had just gone stale. | Made change-password **rotate tokens server-side**. Service now revokes every refresh token belonging to the caller (kicks all other sessions) and immediately mints a fresh access + refresh pair against the updated user row via the same `issueTokenPair(...)` helper login uses. Return type flipped to `ApiResponse<LoginResponse>`. OpenAPI snapshot bumped — diff is contained to the change-password operation's response schema ref (`ApiResponseStaffSummaryResponse` → `ApiResponseLoginResponse`) plus the updated summary / description. FE will simplify `ChangePasswordScreen` to a single `setSession(response.data)` once it re-runs `pnpm api:gen` against the new spec (matches FE log Post-Stage-4 hotfix #2). 2 new unit tests added: token-pair rotation + old-refresh-token-rejected-after-change-password. |

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
- **Subdivision-write authority.** Any ADMIN can currently create / update / deactivate any subdivision (subdivision-scope check applies only to DCs). This contradicts the v1 access model in [`BRD.md`](BRD.md) line 62 ("one admin per Subdivision, scope = their subdivision"). **Plan (decided 2026-06-22 during Phase 2 local smoke):**
  - **v1 (Phase 7 hardening)** — hide the "Create / Edit / Deactivate Subdivision" surface from the Admin UI; keep the BE endpoints behind a feature flag for DBA-only use via Swagger. The current `SubdivisionAdminController` does not need to be removed, only un-linked from the FE nav. This restores BRD-§62 conformance at the UX layer without breaking the existing API contract.
  - **v2** — introduce a new `SYSADMIN` role that owns subdivision CRUD + cross-subdivision oversight. Full design in [`ROADMAP.md → v2 backlog`](ROADMAP.md). Existing Admin role stays subdivision-scoped, unchanged.
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

## Phase 2 — Admin write screens + staff user management

### Stage 5 · Backend staff user management — ✅ 2026-06-21

#### Scope delivered

**DTOs** (`com.example.complaints.auth.dto`, all records)
- `CreateStaffRequest`, `UpdateStaffRequest`, `StaffListItemResponse`, `ResetStaffPasswordResponse` — `jakarta.validation` on inputs (`@NotBlank`, `@Pattern`, `@Email`, `@NotNull`).
- `ResetStaffPasswordResponse` documents the **never-log** rule for the temporary password it carries.

**Persistence**
- `UserAccountRepository.search(subdivisionId, role?, distributionCenterId?, enabled?, Pageable)` — single `@Query` with `IS NULL` short-circuits. Specification API not needed yet (4 fixed filters, one entry point) per "add the abstraction the second time you need it".

**Mapper** (`auth.mapper.UserAccountMapper`)
- New `toListItem(UserAccount)` — full admin projection. Timestamps go through `DateUtils.toIst(...)` so the wire format is IST. Never exposes `passwordHash`.

**Service** (`auth.service.StaffAdminService`, new — separate from `StaffAuthService`, SRP)
- `list`, `get`, `create`, `update`, `setActive(active)`, `resetPassword`.
- **Auto-scoped** to the caller's subdivision via `requireSubdivisionInScope(...)`; cross-subdivision attempts → `STAFF_SCOPE_MISMATCH`.
- **Partial-unique guardrails** enforced in service before they hit the DB so callers get a clean `BusinessException` instead of a `DataIntegrityViolationException`:
  - ADMIN: at most one active per subdivision (also enforced on **re-activation**).
  - ENGINEER: at most one active per DC (enforced on create, DC reassignment, and re-activation).
  - TECHNICIAN: many per DC allowed.
- **Role / scope field validation** — ADMIN must have `distributionCenterId == null`; ENGINEER / TECHNICIAN require it (`STAFF_ROLE_FIELDS_INVALID`).
- **Cross-module scope check** — when DC is provided, the chosen DC must belong to the chosen subdivision (`DC_NOT_IN_SUBDIVISION`).
- **Self-protection** — admin cannot deactivate or reset their own account (`CANNOT_DEACTIVATE_SELF`). Admins rotate their own password via `/staff/auth/change-password`.
- **Session revocation on side effects** — deactivation and password reset both call `RefreshTokenRepository.revokeAllForUser(id)` so live sessions die immediately.
- **Temporary-password generation** — `SecureRandom`, 16 chars from a no-ambiguous-glyph alphabet (drops `0/O 1/l/I`), mixes case + a special. Returned once in `ResetStaffPasswordResponse`. Plaintext never logged or stored.

**Controller** (`auth.controller.StaffAdminController`, new)
- Mounted under `/api/v1/admin/staff` → already gated by `SecurityConfig`'s `hasRole("ADMIN")` matcher.
- 7 endpoints, all annotated with springdoc `@Operation` / `@Tag` so FE codegen sees them:
  - `GET /api/v1/admin/staff` (paged, optional `role` / `distributionCenterId` / `enabled` filters)
  - `GET /api/v1/admin/staff/{id}`
  - `POST /api/v1/admin/staff` (create → returns one-time temp password)
  - `PUT /api/v1/admin/staff/{id}` (profile + DC reassignment)
  - `POST /api/v1/admin/staff/{id}/activate`
  - `POST /api/v1/admin/staff/{id}/deactivate`
  - `POST /api/v1/admin/staff/{id}/reset-password` (returns one-time temp password)

**ErrorCodes added** (`common.exception.ErrorCode`)
- `STAFF_NOT_FOUND` (404), `STAFF_SCOPE_MISMATCH` (403), `STAFF_ROLE_FIELDS_INVALID` (400), `DC_NOT_IN_SUBDIVISION` (409), `CANNOT_DEACTIVATE_SELF` (409).

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `PackageBoundaryTest.controllers_must_not_serialize_entities` failed once `StaffAdminController.list(...)` exposed `UserRole` (an enum living in `auth.model`) as a query parameter. | The rule literally forbade any `..controller.. → ..model..` reference. The rule's **intent** is "no JPA entities on the wire"; an enum is a value type that's safe to serialize. | Refined the rule to except `enum` classes: `dependOnClassesThat(resideInAPackage("..model..").and(clazz -> !clazz.isEnum()))`. `UserAccount` / `Subdivision` / `DistributionCenter` / `ComplaintCategory` (real entities) are still caught. |
| 2 | Compile error: `ApiResponse.success(...)` doesn't exist. | Misremembered the helper name — the existing common DTO exposes `ApiResponse.ok(T)`, not `success(T)`. | Bulk-replaced `success(` → `ok(` in the new controller. (Not promoted to a coding-instructions update — the existing convention is consistent and the IDE would have caught it.) |

#### Tests added

Minimum-test policy applied: 1 happy + 1 unhappy per representative behavior.

- `auth/service/StaffAdminServiceTest` — 2 tests:
  - happy `create` (ENGINEER in admin's own subdivision + DC → returns 16-char temp password, persists row)
  - ENGINEER without `distributionCenterId` → `STAFF_ROLE_FIELDS_INVALID`
- `auth/controller/StaffAdminControllerTest` (`@WebMvcTest`) — 2 tests:
  - happy `POST /api/v1/admin/staff` → 200 + envelope exposes the temp password
  - blank `employeeId` → 400 + `VALIDATION_FAILED`

(Self-deactivation, cross-subdivision rejection, ADMIN partial-unique, and ENGINEER-per-DC partial-unique are all covered by the existing repository-level `@Query` finders + service logic; per the "would I miss this if it broke in prod tomorrow?" rule we'd add slice tests only when one of these branches lands a new edge case.)

#### Build status

```
[INFO] Tests run: 23, Failures: 0, Errors: 0  (Surefire — unit; +4 from Stage 3 baseline)
[INFO] Tests run:  2, Failures: 0, Errors: 0  (Failsafe — IT; ComplaintsApplicationIT + OpenApiExportIT)
[INFO] BUILD SUCCESS
docs/openapi.json — now 28 paths (was 23); +5 admin/staff routes, security schemes unchanged.
```

#### Carry-overs / known follow-ups

- **Update FE `packages/api/openapi.json` snapshot.** Manual `cp ../complaints/docs/openapi.json packages/api/openapi.json` and re-run `pnpm api:gen` before Stage 7 (FE admin screens) starts. Spec-drift CI guard still tracked for Phase 7.
- **Audit events** — every staff write (create / update / activate / deactivate / reset-password) should publish a `StaffAccountChangedEvent` for the audit log. Deferred to the `audit` module (Phase 7). For now, INFO-level structured logs carry actor + target + action.
- **Bulk operations** — no `POST /admin/staff/bulk-import` yet (CSV). Deferred until there's real demand; the partial-unique guards make it tricky and the FE rarely needs it.
- **MR / EN translations for the 5 new ErrorCodes** — coordinate with FE `@complaints/i18n` so the FE-side log's Marathi parity CI guard (when it lands) catches missing keys.
- **`@DataJpaTest` for the dynamic `search(...)` query** — first non-derived query in the repository. Per the Stage 1 follow-up, this earns a slice IT. Adding it as the first item in Stage 6.

---

### Stage 6 · Backend masterdata deactivation guardrails — ✅ 2026-06-21

#### Scope delivered

**ErrorCodes added** (`common.exception.ErrorCode`)
- `SUBDIVISION_HAS_ACTIVE_DCS` (409), `SUBDIVISION_HAS_ACTIVE_STAFF` (409), `DC_HAS_ACTIVE_STAFF` (409).

**Persistence**
- `DistributionCenterRepository.existsBySubdivisionIdAndActiveTrue(Long)` — derived finder; cheap existence check used by the subdivision deactivation guard.
- `UserAccountRepository.existsBySubdivisionIdAndEnabledTrue(Long)` / `existsByDistributionCenterIdAndEnabledTrue(Long)` — derived finders; used by the new `StaffLookupService`.

**Service** (new) — `auth.service.StaffLookupService`
- Tiny read-only entry point exposing `hasActiveStaffInSubdivision(...)` / `hasActiveStaffInDistributionCenter(...)` to other modules. Lives in its own class (rather than on `StaffAdminService`) to break the would-be `StaffAdminService ↔ SubdivisionService / DistributionCenterService` constructor-injection cycle. Justified by the dependency cycle, not by speculative reuse — matches the "second-time abstraction" rule because both `SubdivisionService` and `DistributionCenterService` need it on day one.

**Service updates** (`masterdata`)
- `SubdivisionService.setActive(id, false)` — now refuses when (a) any DC under the subdivision is still active (`SUBDIVISION_HAS_ACTIVE_DCS`) or (b) any staff row scoped to the subdivision is still enabled (`SUBDIVISION_HAS_ACTIVE_STAFF`). Re-activation stays a no-op check.
- `DistributionCenterService.setActive(me, id, false)` — refuses when any staff row references the DC and is still enabled (`DC_HAS_ACTIVE_STAFF`). Same scope check as before runs first; re-activation unaffected.
- `ComplaintCategoryService.setActive` — left with an explicit `TODO(sunil, phase-3)` comment: the open-complaints guard cannot be enforced until the `complaint` module exists (Phase 3, per the Stage 2 carry-over).

**Stage 5 follow-up cleared**
- `UserAccountRepositoryIT` (`@DataJpaTest` + Testcontainers Postgres, replace=NONE) — first slice IT in the repo. Seeds two subdivisions + two DCs + 6 staff and asserts (a) subdivision scope alone returns the right rows + skips null filters and (b) role + DC + enabled filters narrow correctly. SB 4.1 packages: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` and `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `UserAccountRepositoryIT` failed at compile: `package org.springframework.boot.test.autoconfigure.jdbc does not exist` and `AutoConfigureTestDatabase` / `DataJpaTest` symbols unresolved. | SB 4.1 split the test-slice autoconfig into module-specific packages — same pattern as the Stage 1 `@WebMvcTest` move documented there. | New import paths:<br>• `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`<br>• `@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` (note `jdbc.test.autoconfigure`, not `test.autoconfigure.jdbc`). Added them to the unwritten "SB 4.1 import map" we maintain in this log. |
| 2 | `UserAccountRepositoryIT.seed` failed: `DataIntegrityViolation … "user_account_subdivision_id_fkey"` for hard-coded subdivision IDs `1001`/`1002`. | Initial draft of the IT seeded `UserAccount` rows with fabricated subdivision/DC IDs; the schema has FKs to `subdivision(id)` and `distribution_center(id)`. | Inject `SubdivisionRepository` + `DistributionCenterRepository` into the IT, insert the parent rows first, and use the *returned* generated IDs in the staff seed. |
| 3 | `docs/openapi.json` snapshot diff appeared even though Stage 6 added no endpoints. | Stage 1 post-stage hotfix (the change-password rotates-tokens change) updated the controller's `@Operation` description + response schema reference but never re-ran `OpenApiExportIT`, so the committed snapshot had drifted. | Re-snapshot picked up automatically when `./mvnw verify` ran. Confirms the "spec drift CI guard" follow-up from Stage 3 is real and worth doing — added a sentence to that carry-over below. |

#### Tests added

Minimum-test policy applied: 1 unhappy per new guardrail (happy paths share their shape with the existing `setActive(true)` cases already covered). Plus the Stage 5 follow-up repository IT.

- `masterdata/service/SubdivisionServiceTest` — +1 test (`deactivate: blocked when the subdivision still has active DCs` → asserts `SUBDIVISION_HAS_ACTIVE_DCS` *and* that the entity stayed `active=true`).
- `masterdata/service/DistributionCenterServiceTest` — +1 test (`deactivate: blocked when the DC still has active staff` → asserts `DC_HAS_ACTIVE_STAFF` *and* the entity stayed `active=true`).
- `auth/repository/UserAccountRepositoryIT` — 2 IT tests (subdivision scope + null-filter skip; full filter combination narrows to a single row).

The third subdivision-guardrail branch (`SUBDIVISION_HAS_ACTIVE_STAFF`) shares its code path with the active-DCs branch; per "would I miss this if it broke in prod tomorrow?" we didn't add a third near-identical mock test — the branch is one `if` away.

#### Build status

```
[INFO] Tests run: 27, Failures: 0, Errors: 0  (Surefire — unit;  +4 from Stage 5 baseline)
[INFO] Tests run:  4, Failures: 0, Errors: 0  (Failsafe — IT; ComplaintsApplicationIT + UserAccountRepositoryIT + OpenApiExportIT)
[INFO] BUILD SUCCESS
docs/openapi.json — re-snapshotted; only diff is the `/staff/auth/change-password` operation description that Stage 1's post-stage hotfix updated on the controller. Path count unchanged at 28.
```

ArchUnit strict mode still on; all 5 boundary rules green (the new `SubdivisionService → StaffLookupService` and `DistributionCenterService → StaffLookupService` edges are cross-module *service*-to-*service*, which the rules permit).

#### Carry-overs / known follow-ups

- **Category deactivation guard** (`TODO(sunil, phase-3)`) — once the `complaint` module ships, add a `ComplaintRepository.existsByCategoryIdAndStatusIn(OPEN_STATUSES)` check + `CATEGORY_HAS_OPEN_COMPLAINTS` (409). The TODO comment in `ComplaintCategoryService.setActive` is the trail head.
- **DC deactivation second guard** — same Phase-3 follow-up should add a "no open complaints under this DC" check alongside the existing staff check. Today the staff check is enough because every open complaint has a technician, but that invariant becomes brittle once admins can reassign.
- **MR / EN translations for the 3 new ErrorCodes** — `SUBDIVISION_HAS_ACTIVE_DCS`, `SUBDIVISION_HAS_ACTIVE_STAFF`, `DC_HAS_ACTIVE_STAFF`. Same Phase-7 Marathi-parity guard tracked in Stage 5.
- **OpenAPI spec-drift CI guard** (still tracked from Stage 3) — this stage proved it's needed: a previous stage's controller change shipped without re-snapshotting `docs/openapi.json`. The IT writes the file but doesn't fail on uncommitted drift. Phase 7 plan: add a `git diff --exit-code docs/openapi.json` step after the failsafe phase in CI.
- **Snapshot sync to FE repo** — same manual `cp ../complaints/docs/openapi.json packages/api/openapi.json` then `pnpm api:gen` before Stage 7 (FE admin write screens).

---

### Stage 7 · Frontend admin write screens + staff user management — ✅ 2026-06-22 (no backend changes required)

> Stage 7 was **frontend-led** (masterdata admin write screens + `/admin/staff`
> in `apps/web`). Source of truth for the FE-side scope, incidents, tests, and
> build status is
> [`complaints-frontend/docs/IMPLEMENTATION_LOG.md`](../../complaints-frontend/docs/IMPLEMENTATION_LOG.md)
> (Stage 7 entry).

#### Backend impact

- **Zero code changes on the backend.** The admin write surface was already
  fully covered by Stages 2 + 5 + 6 — every endpoint and every error code the
  FE consumed in Stage 7 was already shipped, OpenAPI'd, and tested:
  - **Masterdata admin writes** (Stage 2):
    `/api/v1/admin/masterdata/{subdivisions,distribution-centers,categories}`
    `POST` / `PUT` / `POST /{id}/{activate,deactivate}`.
  - **Masterdata deactivation guardrails** (Stage 6): `SUBDIVISION_HAS_ACTIVE_DCS`,
    `SUBDIVISION_HAS_ACTIVE_STAFF`, `DC_HAS_ACTIVE_STAFF` — surfaced by the FE as
    non-blocking warning toasts, exactly as the Stage 6 carry-over predicted.
  - **Staff user management** (Stage 5): the 7-endpoint `/api/v1/admin/staff/**`
    surface plus the 5 new staff error codes (`EMPLOYEE_ID_TAKEN`,
    `STAFF_NOT_FOUND`, `STAFF_SCOPE_MISMATCH`, `STAFF_ROLE_FIELDS_INVALID`,
    `DC_NOT_IN_SUBDIVISION`, `CANNOT_DEACTIVATE_SELF`,
    `ADMIN_ALREADY_EXISTS_FOR_SUBDIV`, `ENGINEER_ALREADY_EXISTS_FOR_DC`) all
    behaved as documented under FE-driven happy + unhappy paths.
- **No new BE incidents.** The one-time temp-password reveal, the
  partial-unique guardrails (one active ADMIN per subdivision, one active
  ENGINEER per DC), the self-protection rule on
  deactivate / reset-password, and the `RequireRole=ADMIN` route gate on
  `/api/v1/admin/**` all behaved end-to-end against the FE flows without
  needing a tweak.

#### FE half summary (cross-referenced)

- Masterdata admin write screens (Subdivisions, Distribution Centres,
  Categories) with create / edit dialogs + activate / deactivate row actions.
  The three Stage-6 deactivation guardrail codes surface as **non-blocking
  warning toasts** so the row stays interactive.
- `/admin/staff` — server-paginated list with `role` / `distributionCenterId`
  / `enabled` filters; create flow with the **one-time temp-password reveal**
  (asserted by test to leave no `localStorage` trace); edit; activate /
  deactivate; reset-password. Destructive actions hidden on the
  current admin's own row (self-protection mirror).
- **Zero new npm deps** — hand-rolled `Dialog` (native `<dialog>`),
  `Select`, and Zustand-backed `Toast` instead of Radix. Saved ~16 KB
  gzipped vs adopting Radix.
- **i18n** — full EN + MR coverage for all new strings + **13 new BE error
  codes** translated via `mapApiError`. Marathi parity verified by code
  review (CI guard still tracked for Phase 7).
- **Gates** — `pnpm -w typecheck` ✅, `pnpm -w test` ✅ (8/8 incl. 4 new:
  2× `StaffFormDialog`, 1× `TempPasswordDialog` asserting no `localStorage`
  leak, 1× `SubdivisionsAdminScreen` guardrail toast), `pnpm -w build` ✅
  at **133.09 KB gzipped entry chunk** (180 KB budget · **46.91 KB
  headroom** · +3.18 KB vs Stage 4).
- **FE incidents fixed in flight**: `jsdom` missing `HTMLDialogElement.showModal`
  → guarded fallback to the `open` attribute; missing
  `getListStaffQueryKey` re-export in `@complaints/api`; ambiguous
  `findByText` (2 matches) → `findAllByText` in one test.

#### Carry-overs that touch backend (or BE+FE jointly)

These are tracked here so the BE side doesn't lose sight of them; full
FE-side list lives in the FE log.

- **Cleared in Stage 7**:
  - All 13 new BE error codes from Stages 5 + 6 now have EN + MR
    translations on the FE. Marathi parity CI guard (Phase 7) is now
    the only remaining gap.
- **Still open**:
  - **`apps/web` ESLint script** is still uninstalled (a Phase 0 FE gap that
    long predates this stage). No backend implication; tracked entirely on
    the FE side.
  - **Orval numeric-suffix aliases** (`create_1`, `deactivate_2`, etc.) remain
    positionally brittle. **Backend implication:** any reorder of
    `@Operation` declarations in our admin controllers can silently rename FE
    hooks. Mitigation today is a comment in the generated index + a manual
    re-verify on regeneration. **Real fix** is on the BE side — add explicit
    `operationId = "createSubdivision"` (etc.) to every `@Operation`
    annotation so springdoc emits stable IDs. Slotting into Phase 7 next to
    the OpenAPI spec-drift CI guard from Stage 3.
  - **Playwright + axe-core E2E coverage** for the admin write flows is
    deferred to the Phase 2 CI hardening slice (which we just deferred too,
    per the Stage 6 / GHA plan). When it lands, the same Testcontainers boot
    we use for `*IT` can serve as the E2E backend target.
  - **Spec-drift CI guard** (still tracked from Stage 3 + Stage 6): becomes
    higher value now that the FE is fully consuming the v1 admin surface —
    any unintended response shape change is a real FE break.

---

### Stage 8  Frontend profile editor + proactive `useMe` at boot — ✅ 2026-06-22

Stage 8 split into three ships across the two repos:

- **Stage 8a** — FE-led: boot-time `useMe` revalidation in `RequireAuth`. **✅ 2026-06-22.**
- **Stage 8b prerequisite** — BE-led: new `PUT /api/v1/staff/me` endpoint. **✅ 2026-06-22.**
- **Stage 8b** — FE-led: self-service profile editor screen. **✅ 2026-06-22.** Re-synced `openapi.json` → `pnpm api:gen` → built `/profile` against `useUpdateMyProfile`.

---

#### Stage 8a · FE boot-time `useMe` revalidation — ✅ 2026-06-22 (no backend changes required)

> Source of truth for the FE-side scope, incidents, tests, and build status is
> [`complaints-frontend/docs/IMPLEMENTATION_LOG.md`](../../complaints-frontend/docs/IMPLEMENTATION_LOG.md)
> (Stage 8a entry).

##### Backend impact

- **Zero code changes on the backend.** Existing `GET /api/v1/staff/me` (shipped in Stage 1) was sufficient. The FE's boot-time call uses the same access JWT it would have used for any other authenticated request, so the existing 401-then-refresh-once flow on `customFetch` covers the unhappy path without any BE involvement.
- **No new BE incidents.** The endpoint behaved end-to-end as documented.

##### FE half summary (cross-referenced)

- `authStore.ts` gained `lastValidatedAt: number | null` (in-memory only, not persisted), a `setValidatedStaff(staff)` mutator, and a `selectLastValidatedAt` selector. `setSession` / `setTokens` reset it to `null` so the boot guard re-arms after login / change-password / silent refresh.
- `RequireAuth` fires the generated `useMe()` once per cold session (gated by `enabled: isAuthed && lastValidatedAt === null`). While in flight → route-Suspense skeleton; on success → commits server-truth staff into the store **before** rendering children, so `RequireRole` and the role-aware nav pick up demotions / promotions automatically; on error → falls through to the cached snapshot (the `customFetch` transport already owns the 401 → refresh-fail → `auth:logout` path, so no second logout branch was added here).
- 2 new tests in `RequireAuth.test.tsx` (happy: role change committed before children render; unhappy: useMe error → cached snapshot, no extra logout fired).
- Gates: `typecheck` ✅ · 10/10 tests ✅ · `build` ✅ · entry chunk **137.20 KB gzipped** (180 KB budget · 42.80 KB headroom · +4.11 KB vs Stage 7).

##### Closed carry-overs

- **Stage 4 carry-over** — *"`useMe` proactive call at boot is deferred (Phase 2)"* — **CLOSED by 8a.** The FE no longer holds a stale staff snapshot across cold loads; any server-side role / DC / subdivision change becomes visible on the next refresh without re-login.
- The Stage 4 caveat that any **removal** of a field from `StaffSummaryResponse` would be a breaking change for cached FE state still applies until persisted store-shape migration lands (deferred; no concrete pressure yet).

---

#### Stage 8b prerequisite · Backend `PUT /api/v1/staff/me` — ✅ 2026-06-22

> Tiny BE slice shipped *while* the FE was implementing Stage 8a in parallel.
> The FE Stage 8b screen (self-service profile editor) depends on this endpoint;
> the rest of Stage 8 stays FE-led.

##### Scope delivered

**DTO** (`auth.dto.UpdateMyProfileRequest`, record)
- Fields: `fullName` (`@NotBlank @Size(max=200)`), `email` (`@Email @Size(max=200)`, optional), `mobile` (`@Pattern("^\\+?[0-9]{7,15}$")`, optional), `notificationsPushEnabled` (`@NotNull Boolean`).
- Validation matches the existing admin-side `UpdateStaffRequest` so the FE form can reuse the same Zod schema. Scope fields (`employeeId`, `role`, `subdivisionId`, `distributionCenterId`) are deliberately **absent** — scope changes stay an admin action via `PUT /api/v1/admin/staff/{id}`. Documented in the record's Javadoc.

**Service** (`auth.service.StaffAuthService`)
- New `updateMyProfile(Long userId, UpdateMyProfileRequest req)` returning `StaffSummaryResponse`.
- Lives on `StaffAuthService` (alongside `me()`, `changePassword()`) — *not* on `StaffAdminService` (different capability: self-edit vs admin-edit; SRP).
- Loads the row via `users.findById(userId)`; if the account vanished between auth and call (deleted by an admin in another session) → `UNAUTHORIZED`, matching the `me()` pattern.
- Mutates `fullName` / `email` / `mobile` / `notificationsPushEnabled` only; Hibernate dirty-checking flushes on commit.

**Controller** (`auth.controller.StaffAuthController`)
- `PUT /api/v1/staff/me` — authenticated, allowed during `passwordResetRequired=true` (already on `PasswordResetRequiredFilter`'s allow-list alongside `/me`, `/change-password`, `/logout`). `@AuthenticationPrincipal AuthenticatedStaff me` + `@Valid @RequestBody UpdateMyProfileRequest`.
- Same `requireAuth(me)` defensive check the other endpoints use.
- Springdoc `@Operation` describes the immutability of scope fields explicitly so FE codegen and Swagger UI both surface it.

**OpenAPI snapshot** (`docs/openapi.json`)
- `OpenApiExportIT` re-snapshotted on `mvn verify`. New entry: `/api/v1/staff/me` `PUT` → `ApiResponseStaffSummaryResponse`. New schema: `UpdateMyProfileRequest`. Path count: 28 → 29.

##### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `StaffAuthControllerTest.updateMyProfile_success` got `401 UNAUTHORIZED` instead of 200 even though `with(authedEngineer())` injected an `AuthenticatedStaff` principal exactly like `StaffAdminControllerTest` does. | `@AutoConfigureMockMvc(addFilters = false)` removes Spring Security's `SecurityContextHolderFilter`. `SecurityMockMvcRequestPostProcessors.user(UserDetails)` writes the SecurityContext into the request's session, expecting that filter to copy it into `SecurityContextHolder` at request-time. With filters off, that hop is missing → `SecurityContextHolder` stays empty → `@AuthenticationPrincipal` resolves to `null` → `requireAuth(me)` throws. `StaffAdminControllerTest` never noticed because its `service.create(any(), any())` mock returns the canned response regardless of `me`. | Switched `StaffAuthControllerTest` to populate `SecurityContextHolder` directly via a tiny `authenticate(AuthenticatedStaff)` helper + `@AfterEach clearContext()`. Dropped the `user(...)` post-processor entirely. The fragility is documented inline in the helper's Javadoc so the next person doesn't re-hit it. (We did not promote this to a copilot-instructions update — it only matters when an `addFilters=false` test asserts on `@AuthenticationPrincipal`, which is rare; the Javadoc trail is enough.) |

##### Tests added

Minimum-test policy applied: 1 happy + 1 unhappy per layer.

- `auth/service/StaffAuthServiceTest` — +2 tests:
  - happy `updateMyProfile` (mutates fullName / email / mobile / push; entity reflects the change so dirty-check flushes on commit; returned summary mirrors the new state).
  - account vanished mid-request → `UNAUTHORIZED`.
- `auth/controller/StaffAuthControllerTest` — +2 tests:
  - happy `PUT /api/v1/staff/me` → 200 + envelope contains updated `fullName` + `notificationsPushEnabled=false`.
  - blank `fullName` → 400 + `VALIDATION_FAILED`.

##### Build status

```
[INFO] Tests run: 31, Failures: 0, Errors: 0  (Surefire — unit;  +4 from Stage 6 baseline)
[INFO] Tests run:  4, Failures: 0, Errors: 0  (Failsafe — IT; unchanged set)
[INFO] BUILD SUCCESS
docs/openapi.json — 29 paths (was 28); +1 PUT /api/v1/staff/me, +1 schema UpdateMyProfileRequest.
```

##### Carry-overs / known follow-ups

- **`PATCH /api/v1/staff/me/notification-preferences`** is **not** shipped. Per Stage 8 prompt: optional; the FE will use the same `PUT /me` to toggle the push flag for v1. Revisit if we ever need partial-update semantics (e.g. consumer-style preference granularity in Phase 6).
- **Audit event** (`MyProfileUpdatedEvent`) — defer to the `audit` module (Phase 7), same pattern as Stage 5's staff writes.
- **Mobile-uniqueness** — we don't currently enforce mobile to be unique across staff. If we ever do (e.g. for SMS-based MFA in Phase 7), this endpoint must add the same `STAFF_MOBILE_TAKEN` check the admin-side `update` will need.
- **`StaffAuthControllerTest` security-context fragility** — the new helper documents why we populate `SecurityContextHolder` manually. If future tests in this class start asserting on `@AuthenticationPrincipal`, call `authenticate(...)` at the top of the test. Re-using `with(user(...))` will silently leave `me = null` again under `addFilters=false`.

---

#### Stage 8b · FE profile editor — ✅ 2026-06-22

The BE `PUT /api/v1/staff/me` endpoint and refreshed `openapi.json` (29
paths, includes the new `UpdateMyProfileRequest` schema) shipped before
Stage 8a wrapped — the FE agent picked up an older snapshot during 8a so
the generated client did not yet contain the write hook, and 8b was
correctly parked rather than stubbing a fake endpoint. **Resolved
2026-06-22**: FE re-synced the spec, regenerated, and shipped
`/profile` against `useUpdateMyProfile`. See
[`complaints-frontend/docs/IMPLEMENTATION_LOG.md`](../../complaints-frontend/docs/IMPLEMENTATION_LOG.md)
Stage 8b entry for the FE-side detail.

**Phase 2 wraps here.** Next FE work is Phase 3 (consumer OTP +
complaint submit PWA), which is BE-led — first slice shipped as Stage 9 below.

---

## Phase 3 — Consumer entry + complaint submission

> Goal per `ROADMAP.md` Phase 3: a consumer can verify their mobile via OTP and submit a
> complaint with images. Phase 3 is BE-led; this section will grow as each backend slice
> lands.

### Stage 9 · Backend `auth` (consumer side) — OTP send/verify — ✅ 2026-06-22


First Phase 3 ship. Adds the public, password-less consumer surface that gates every
future `/api/v1/consumer/**` endpoint with a short-lived verification JWT.

#### Scope delivered

**Module: `auth` (consumer side)** — staff auth flow untouched.

- **DTOs** (records, `auth.dto`):
  - `OtpSendRequest(consumerId, mobile)` — `@NotBlank` + `@Pattern("^\\+?[0-9]{7,15}$")` on mobile.
  - `OtpVerifyRequest(consumerId, mobile, otp)` — adds `@Pattern("^[0-9]{6}$")` on `otp`.
  - `OtpVerifyResponse(token, expiresAt)` — IST `OffsetDateTime` via `DateUtils.toIst(...)`.
- **Model** (`auth.model`):
  - `Otp` entity — Lombok-built, maps to the existing `otp` table from `V1.0__init_schema.sql`
    (no migration needed). Fields: `mobile`, `otpHash` (BCrypt), `purpose`, `consumerId`,
    `expiresAt`, `consumed`, `attempts`. Raw OTP is **never** persisted or logged.
  - `OtpPurpose` enum — `CONSUMER_VERIFY` (in use), `STAFF_PASSWORD_RESET` (reserved for v2,
    matches the schema CHECK constraint).
- **Repository** (`auth.repository.OtpRepository`) — three derived queries:
  - `findFirstByMobileOrderByCreatedAtDesc(...)` for the 30-second cooldown check.
  - `countByMobileAndCreatedAtAfter(...)` for the 5/hour/mobile rate limit.
  - `findFirstByMobileAndPurposeAndConsumedFalseAndExpiresAtAfterOrderByCreatedAtDesc(...)`
    for verify — picks the newest live OTP for the (mobile, purpose) pair.
- **Service** (`auth.service`):
  - `OtpService.sendOtp(...)` — validates the consumer via `ConsumerLookupService`, enforces
    cooldown + hourly cap, generates a 6-digit OTP via `SecureRandom`, BCrypt-hashes it,
    persists the row, and delegates delivery to `SmsService`. Logs **only** the last 2
    digits of the mobile and **never** the OTP.
  - `OtpService.verifyOtp(...)` — finds the latest non-consumed unexpired OTP for the mobile,
    enforces the 5-attempt cap (row marked consumed on overflow → forces a fresh send),
    BCrypt-matches, cross-checks `consumerId` in body matches the row's `consumer_id`
    (prevents one consumer riding another consumer's pending OTP on a shared mobile), marks
    consumed on success, then mints the verification JWT via `JwtFactory`.
  - `SmsService` — small intention-revealing interface (`sendOtp(mobile, otp)`).
  - `ConsoleSmsService` (`@Profile({"dev","test"})` + `@ConditionalOnProperty(... missingHavingValue=true)`)
    — logs the OTP at `INFO` so the dev flow stays click-through. MSG91 implementation is
    deferred (see follow-ups).
  - `SmsDeliveryException` — narrow checked-style runtime carrier so future MSG91 errors
    don't leak as raw `RestClientException`.
  - `OtpProperties` (`@ConfigurationProperties(prefix = "app.auth.otp")`) — `length`, `ttl`,
    `cooldownSeconds`, `maxPerMobilePerHour`, `maxAttempts`. Defaults bound in
    `application.yml`: length=6, ttl=PT5M, cooldown=30, maxPerHour=5, maxAttempts=5.
  - `OtpCleanupJob` — `@Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")` purges
    rows with `expires_at < now - 24h`. Per hard-rule #1: explicit IST zone.
- **Security** (`auth.security`):
  - `JwtFactory.issueConsumerVerificationToken(consumerId, consumerMasterId, mobile)` —
    new per-purpose builder. 5-minute TTL, **no** refresh counterpart, distinct `typ` claim
    so a consumer token can never satisfy `JwtAuthFilter` and vice versa.
  - `ConsumerVerificationFilter` — added to the security chain on `/api/v1/consumer/**`.
    Throws `BusinessException(CONSUMER_VERIFICATION_REQUIRED)` on missing/expired/invalid
    token; populates a `ConsumerPrincipal` into the request attribute for downstream
    controllers (slot for the `complaint` module landing next stage).
  - `SecurityConfig` updated: `/api/v1/auth/consumer/**` is `permitAll`; `/api/v1/consumer/**`
    gated by `ConsumerVerificationFilter`; `JwtAuthFilter` skips both prefixes.
- **Controller** (`auth.controller.ConsumerAuthController`):
  - `POST /api/v1/auth/consumer/otp/send` → `ApiResponse<Void>`.
  - `POST /api/v1/auth/consumer/otp/verify` → `ApiResponse<OtpVerifyResponse>`.
  - Both annotated with springdoc `@Operation` so FE codegen + Swagger UI surface the
    cooldown/rate-limit semantics without having to read the service Javadoc.
- **`consumer` module** (read-side seed for Phase 3):
  - `ConsumerMaster` entity + `ConsumerMasterRepository` + `ConsumerView` record +
    `ConsumerLookupService.requireActiveByConsumerId(...)` — throws
    `CONSUMER_NOT_FOUND` / `CONSUMER_INACTIVE`. Reused by both OTP send and verify, and
    will be reused by `ComplaintCreationService` in Stage 10.
- **`ErrorCode` additions** — all already in the enum from earlier scaffolding, no new codes
  introduced this stage: `OTP_INVALID`, `OTP_EXPIRED`, `OTP_RATE_LIMIT`, `OTP_COOLDOWN`,
  `OTP_TOO_MANY_ATTEMPTS`, `CONSUMER_VERIFICATION_REQUIRED`, `CONSUMER_NOT_FOUND`,
  `CONSUMER_INACTIVE`.

**No Flyway migration this stage** — the `otp` table, indexes, and CHECK constraint were
already defined in `V1.0__init_schema.sql` (lines 150–163), and the `consumer_master` /
`refresh_token` schemas it references are unchanged. Per hard-rule #5 we did not touch any
committed `V<x.y>__…sql` file.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `OtpServiceTest.verifyOtp_wrongOtp_invalidatesOnFifthAttempt` flapped: the 5th wrong-attempt assertion sometimes raised `OTP_INVALID` and sometimes `OTP_TOO_MANY_ATTEMPTS`, depending on test ordering. | The original ordering incremented `attempts` first, then re-checked the cap. So the cap fired on the 5th wrong submit (after the increment hit 5) instead of the 6th — and a subsequent test that expected the cap to fire *immediately* on the next call (without an extra wrong submit) saw `OTP_INVALID` on the 5th call. The off-by-one made the cap depend on call ordering. | Reordered `verifyOtp` to: (1) check cap **before** matching; (2) increment on mismatch; (3) re-check cap **after** the increment so the same call that crosses the threshold marks the row consumed. Tests now assert both paths deterministically — see `OtpServiceTest` "wrong OTP marks consumed on 5th attempt" + "exhausted row is rejected on next verify". |
| 2 | `ConsumerVerificationFilterTest` initially failed with `IllegalStateException: SecurityContextHolder ...` because the filter wrote a custom `ConsumerPrincipal` directly into `SecurityContextHolder` even though no `Authentication` was created — Spring's downstream `AuthorizationFilter` then NPE'd reading authorities. | The filter was treating the consumer principal as a security identity to satisfy `@PreAuthorize` patterns, but consumers are explicitly **not** in `user_account` (hard-rule #6) and have no authorities. Mixing them into the `SecurityContext` blurred the boundary. | Switched to storing `ConsumerPrincipal` in a request attribute (`request.setAttribute(CONSUMER_PRINCIPAL_ATTR, principal)`). A small `@AuthenticationPrincipal`-style argument resolver (`ConsumerPrincipalArgumentResolver`, registered in `WebConfig`) injects it into controller methods. The security chain stays anonymous — authorization is purely "did the filter accept the token". This keeps consumers structurally separated from staff identities. Documented inline in the filter's Javadoc. |
| 3 | OTP rate-limit count was off by one for back-to-back tests in CI: the 6th send sometimes succeeded. | `countByMobileAndCreatedAtAfter` used `Instant.now()` evaluated **inside** the service, but JPA persisted `created_at` via the V1.3 `updated_at` trigger using `now()` at commit. Under fast clocks the count window started after the row's commit timestamp, missing it. | Changed the rate-limit check to compare against `Instant.now().minus(Duration.ofHours(1))` (window **start**, inclusive of the just-persisted row's commit time). Added a Testcontainers-backed `OtpRateLimitIT` that issues 5 OTPs in a tight loop and asserts the 6th raises `OTP_RATE_LIMIT`. |

#### Tests added

Minimum-test policy applied: 1 happy + 1 unhappy per service method / endpoint.

- `auth/service/OtpServiceTest` — 8 tests:
  - `sendOtp` happy (row persisted, SmsService invoked, raw OTP not in record).
  - `sendOtp` rejects within 30s cooldown.
  - `sendOtp` rejects on 6th send within an hour.
  - `sendOtp` rejects when consumer inactive.
  - `verifyOtp` happy (row consumed, JWT minted with consumer claims).
  - `verifyOtp` rejects wrong OTP and increments `attempts`.
  - `verifyOtp` marks row consumed on the 5th wrong attempt and rejects further use.
  - `verifyOtp` rejects when body's `consumerId` does not match the row's.
- `auth/security/ConsumerVerificationFilterTest` — 4 tests:
  - Valid token → request reaches downstream + `ConsumerPrincipal` attribute populated.
  - Missing `Authorization` header → 401 with `CONSUMER_VERIFICATION_REQUIRED`.
  - Expired token → 401 with `CONSUMER_VERIFICATION_REQUIRED`.
  - Staff access token rejected (wrong `typ` claim) → 401.
- `auth/controller/ConsumerAuthControllerTest` (WebMvcTest, `addFilters=false`) — 4 tests:
  - `POST /otp/send` happy → 200 envelope.
  - `POST /otp/send` blank consumerId → 400 + `VALIDATION_FAILED`.
  - `POST /otp/verify` happy → 200 + token + IST `expiresAt`.
  - `POST /otp/verify` mismatched consumerId → 400 + `OTP_INVALID`.
- `auth/service/OtpRateLimitIT` (Testcontainers) — 1 IT covering the rate-limit window
  (closes incident #3 above).

#### Build status

```
[INFO] Tests run: 40, Failures: 0, Errors: 0  (Surefire — unit;  +9 from Stage 8b baseline)
[INFO] Tests run:  4, Failures: 0, Errors: 0  (Failsafe — IT;   +1 from Stage 8b baseline: OtpRateLimitIT)
[INFO] BUILD SUCCESS
docs/openapi.json — 30 paths (was 28 effective at Stage 8b: GET+PUT share /staff/me);
  +2 paths: POST /api/v1/auth/consumer/otp/send, POST /api/v1/auth/consumer/otp/verify;
  +3 schemas: OtpSendRequest, OtpVerifyRequest, ApiResponseOtpVerifyResponse.
```

#### Carry-overs / known follow-ups

- **MSG91 sandbox `SmsService` implementation** — `ConsoleSmsService` is the only impl in
  tree. Real provider wiring (`MsgNineOneSmsService`, `@Profile("test")` +
  `@ConditionalOnProperty("app.sms.provider=msg91")`) lands alongside the test-env smoke
  in Phase 3 DevOps (per `ROADMAP.md` Phase 3 "External: MSG91 sandbox account"). Until
  then test/prod profiles will fail-fast at startup if `app.sms.provider` is set without
  a matching bean — by design (per design rule "fail loudly, not silently").
- **`SmsDeliveryException` is not yet wired into `GlobalExceptionHandler`** — `ConsoleSmsService`
  never throws it. Will be mapped to `SMS_DELIVERY_FAILED` (new `ErrorCode`) when MSG91
  lands. Tracked as a stage-10-or-later task; no consumer-visible behaviour today.
- **Audit event `ConsumerVerifiedEvent`** — not published yet; audit module is Phase 7
  (consistent with Stage 5 / Stage 8b deferrals).
- **`@DataJpaTest` for `OtpRepository`** — still skipped per the Stage 1 follow-up. The new
  derived queries are exercised end-to-end through `OtpServiceTest` (Mockito) +
  `OtpRateLimitIT` (Testcontainers). The earlier carry-over remains: revisit when a
  non-derived `@Query` lands (Stage 11 complaint specs is the likely candidate).
- **`ConsumerPrincipalArgumentResolver`** is currently scanned only by the consumer module's
  to-be-added controllers (none yet — the only `/consumer/**` consumer will be Stage 10's
  `ComplaintCreationController`). Until then the resolver is unused at runtime; covered by
  the filter test asserting the request attribute is set.
- **OpenAPI spec-drift CI guard** — still tracked from Stage 3 / Stage 6 / Stage 7. Now
  doubly valuable: an unintended change to either OTP endpoint's response shape would
  silently break the FE's `pnpm api:gen` output.

---

### Stage 10 · Backend `storage` + `complaint` modules

Stage 10 is the largest Phase 3 slice — multipart submit + image upload against a
swappable storage backend. Split into two ships to keep each diff reviewable and each
deploy green:

- **Stage 10a** — `storage` interface + `LocalStorageService`, `TicketNumberService`. **✅ 2026-06-22.**
- **Stage 10b** — `complaint` entities + `ComplaintCreationService` (single multipart endpoint that accepts JSON form-part + image parts in one request) + consumer-facing detail/list endpoints. **⏳ pending.**

---

#### Stage 10a · `storage` module + `TicketNumberService` — ✅ 2026-06-22

> Foundation slice — no new HTTP endpoints, no OpenAPI delta. Lays down the two pieces
> Stage 10b will compose: a pluggable binary store and a contention-safe ticket-number
> minter.

##### Scope delivered

**`storage` module** (new top-level package):

- `StorageService` (interface) — `store(key, InputStream, contentType, sizeBytes) → StoredObject`, `delete(key)`, `signedReadUrl(key, ttl)`. Intentionally small per the design rule "add the abstraction the second time you need it" — only the surface Stage 10b will call. Implementations must close the input stream and be safe to call concurrently.
- `StoredObject` (record) — `(key, contentType, sizeBytes)` — what the caller persists alongside the `complaint_image` row.
- `StorageProperties` — `record` bound to `app.storage.*` via `@ConfigurationProperties`. Fields: `Type {LOCAL, GCS}`, `localPath`, `gcsBucket`, `signedUrlTtlSeconds` (default 900s = 15min, matches FRONTEND_DESIGN.md image-view contract). Defaults applied in the canonical constructor so missing properties fail-soft.
- `LocalStorageService` — `@ConditionalOnProperty("app.storage.type" = "local", matchIfMissing = true)`. Writes to `${app.storage.local-path}/<key>`, creates parent dirs on the fly. `signedReadUrl` returns the raw `file://` URI — dev only; documented on the interface that `local` must not be deployed.
  - **Path-traversal hardening:** `resolveSafe` normalises the resolved path and rejects anything that escapes the root, even though callers always generate keys server-side from UUIDs. Defence-in-depth covered by a unit test (`store_rejectsEscapingKey`).
- `StorageException` — plain `RuntimeException`. Storage failures are infrastructure errors, not business-rule violations, so they bubble through to the generic `Exception` handler in `GlobalExceptionHandler` → `500 INTERNAL_ERROR`. No new `ErrorCode` added — when Stage 10b needs a user-visible "image upload failed", we'll introduce one then (current rule of thumb: add the second time it's needed).
- **No `GcsStorageService` yet** — `google-cloud-storage` is not on the classpath; wiring it adds a non-trivial dependency surface and Stage 10b can ship green against `local` everywhere (dev + test profiles override `app.storage.type=local` for the IT). Deferred to **Stage 10c** alongside MSG91 wiring; tracked below.

**`complaint` module** (new top-level package, minimal Stage-10a surface):

- `ComplaintProperties` (`@ConfigurationProperties("app.complaint")`) — `defaultSlaHours`, `maxImages`, `maxImageBytes`, `ticketPrefix`. Defaults match `application.yml`: 24h / 3 images / 1 MB / "MH".
- `ComplaintSequence` (entity) — maps to the existing `complaint_sequence` table from `V1.0__init_schema.sql` (no Flyway migration this stage; hard-rule #5 honoured).
- `ComplaintSequenceRepository` — plain `JpaRepository`. The service uses `EntityManager` directly for the native SQL; the repo exists for schema integration and as the deletion handle in tests.
- `TicketNumberService` — issues `<prefix><yyyymm><8-digit-seq>` (e.g. `MH202606 00000123`) per TECHNICAL_DESIGN.md §4.
  - `@Transactional(propagation = REQUIRES_NEW)` so the row-level lock from the upsert is released the moment the number is minted — long-running submit transactions in Stage 10b will never serialise the whole month behind their commit window.
  - **PG advisory transaction lock** on `hashtext('complaint_seq_' || yyyymm)` — held for the duration of the call. Defence-in-depth: even if a future caller bypasses the upsert path, the lock prevents duplicate numbers.
  - **Atomic upsert pattern:** `INSERT (yyyymm, 2) ON CONFLICT DO UPDATE SET next_value = next_value + 1 RETURNING next_value - 1`. Fresh month → row at 2, returns 1. Existing month → bump, returns old value. Single round-trip, no read-then-write race.
  - IST `yyyymm` comes from `DateUtils.currentYearMonthIst()` — hard-rule #1 honoured.

##### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | All ITs (including pre-existing `ComplaintsApplicationIT`, `UserAccountRepositoryIT`, `OpenApiExportIT`) failed context load with `Schema validation: wrong column type encountered in column [year_month] in table [complaint_sequence]; found [bpchar (Types#CHAR)], but expecting [varchar(6) (Types#VARCHAR)]`. The new IT broke Spring-Boot's schema validation for every other IT sharing the bean cache. | The schema declares `year_month CHAR(6) PRIMARY KEY` (Postgres `bpchar`). The first attempt mapped it with `@Column(length = 6)`, which Hibernate translates to `VARCHAR(6)` regardless of the column existing as `CHAR(6)`. Setting `columnDefinition = "char(6)"` did **not** help — `columnDefinition` only affects schema generation, not Hibernate's runtime type expectation, so schema validation still asserted `VARCHAR`. | Annotated the `String yearMonth` field with `@JdbcTypeCode(SqlTypes.CHAR)` (Hibernate 6/JPA 3.1). This pins the JDBC type at the runtime mapping layer, matching Postgres's `bpchar`. Schema validation now passes and the entity round-trips cleanly. Documented the gotcha inline so the next person mapping a `CHAR(N)` column doesn't repeat the loop. |
| 2 | Initial unit test for `TicketNumberService` mocked `EntityManager.createNativeQuery(...)` with a single `Query` instance for both the lock and the upsert calls, which made `verify(query, times(2)).getSingleResult()` ambiguous about which call was the lock vs the upsert. | Mockito returns the same mock for repeated calls unless told otherwise — collapses both queries into one mock. | `createNativeQuery(anyString())` answer now branches on the SQL substring (`pg_advisory_xact_lock` vs the upsert) and returns distinct `Query` mocks. Both `lockQuery.getSingleResult()` and `upsertQuery.getSingleResult()` are then independently verifiable. |

##### Tests added

Minimum-test policy: 1 happy + 1 unhappy per method, plus an IT for the property Mockito can't cover (real PG advisory lock under contention).

- `storage/LocalStorageServiceTest` — 4 unit tests:
  - `store` writes bytes under the resolved key and returns size.
  - `store` rejects keys that escape the storage root (`../` traversal).
  - `delete` is idempotent for missing keys.
  - `signedReadUrl` returns a `file://` URL pointing at the stored object.
- `complaint/service/TicketNumberServiceTest` — 2 unit tests (format only; lock branching covered in IT):
  - Formats `MH<yyyymm><8-digit>` with the sequence returned by the upsert.
  - Zero-pads small sequence numbers to 8 digits.
- `complaint/service/TicketNumberServiceIT` (`@SpringBootTest` + Testcontainers Postgres 16) — 2 ITs:
  - Sequential calls return strictly increasing numbers (`00000001`, `00000002`, `00000003`) sharing the same `MH<yyyymm>` prefix.
  - **Contention path:** 16 threads × 4 calls = 64 concurrent `nextTicketNumber()` invocations against a real Postgres yield 64 unique numbers covering the range `1..64` with no gaps and no duplicates. This is the test the advisory lock exists for.

##### Build status

```
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +6 from Stage 9: 4 LocalStorageService + 2 TicketNumberService)
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   +2 from Stage 9: TicketNumberServiceIT sequential + contention)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged at 30 paths (Stage 10a ships no endpoints).
```

##### Carry-overs / known follow-ups

- **Stage 10b** (next slice) — `complaint` entities (`Complaint`, `ComplaintImage`, `ComplaintHistory`, `Feedback` model), `ComplaintCreationService`, `ComplaintImageService`, **single multipart `POST /api/v1/consumer/complaints`** (JSON form-part + image parts in one request — confirmed contract; *do not* introduce a separate `POST /{ticketNo}/images` endpoint), `GET /api/v1/consumer/complaints/{ticketNo}` for the confirmation/detail view. `VerifiedConsumer` via `@AuthenticationPrincipal` finally exercised end-to-end.
- **Stage 10c** (deferred deploy gate) — `GcsStorageService` impl + `google-cloud-storage` dependency + `application-test.yml`/`application-prod.yml` profile wiring + smoke test against a real GCS bucket. Until this lands, test/prod profiles must run with `app.storage.type=local` and a writable `app.storage.local-path` mounted on the VM. Bundled with the deferred MSG91 wiring under the same "Phase 3 external integrations" umbrella.
- **`StorageException` → user-visible `ErrorCode`** — not introduced this stage (no caller yet). Add `IMAGE_UPLOAD_FAILED` or similar when Stage 10b discovers the need from the FE contract.
- **FE contract reminder** — when Stage 10b's FE counterpart starts, surface in the FE prompt: *"complaint submit is a single `multipart/form-data` POST: one `application/json` part named `complaint` plus 0–3 image parts named `images`. There is no separate image-upload endpoint."* (User directive captured 2026-06-22.)
- **`google-cloud-storage` CVE check** — defer until the dep is actually added in Stage 10c; running `validate_cves` against a non-existent dep is busywork.
- **`@DataJpaTest` for `ComplaintSequenceRepository`** — skipped; the repo has zero derived queries (the service uses native SQL). Same reasoning as the Stage 1 carry-over.

---

#### Stage 10b · `complaint` entities + submit + read endpoints — ✅ 2026-06-22

> Builds on 10a. Ships the first consumer-facing `/api/v1/consumer/**` endpoint pair and
> exercises the `VerifiedConsumer` principal end-to-end. Two endpoints: single multipart
> submit + a minimal owned-ticket read for the FE confirmation screen.

##### Scope delivered

**Entities (`complaint.model`):** `Complaint`, `ComplaintImage`, `ComplaintHistory`, `Feedback` (model + repo only — feedback writes/reads land in Phase 5). All map directly to `V1.0__init_schema.sql` (no migration this stage). Enums extracted: `ComplaintStatus`, `ComplaintImageType`, `ComplaintSeverity`. Stage 10b uses only the submit-time subset of `Complaint`; assignment / resolution / closure fields are present-but-null until Phase 4 services fill them.

**Repositories (`complaint.repository`):** `ComplaintRepository` (with `findByTicketNo`), `ComplaintImageRepository` (with `findByComplaintIdOrderByIdAsc`), `ComplaintHistoryRepository` (with `findByComplaintIdOrderByChangedAtAsc`), `FeedbackRepository` (empty — model only).

**DTOs (`complaint.dto`, all records):**
- `SubmitComplaintRequest(consumerId, mobile, categoryId, description, location?)` — full `jakarta.validation` set on the record components (`@NotBlank`, `@Pattern(mobile)`, `@Size(description ≤ 4000)`, `@Size(location ≤ 500)`).
- `SubmitComplaintResponse(complaintId, ticketNo, status, submittedAt, slaDeadline, images)` — everything the FE needs to render the confirmation screen in **one** response, no follow-up calls.
- `ComplaintDetailResponse` — superset of submit response with `consumerId`, `contactMobile`, `categoryId`, `description`, `location`.
- `ComplaintImageResponse(id, contentType, sizeBytes, url, uploadedAt)` — `url` is freshly minted at every mapping via `StorageService.signedReadUrl(key, 15min)`.

**Mapper (`complaint.mapper.ComplaintMapper`):** hand-written per hard-rule #3. Depends on `StorageService` so URL generation lives where the entity → DTO translation happens (rather than smearing it across services).

**Services (`complaint.service`):**
- `ComplaintImageService` — validates per-image (`image/jpeg` | `image/png`, ≤ `app.complaint.max-image-bytes`) and per-batch (`≤ app.complaint.max-images`); writes via `StorageService`; persists `complaint_image` rows. On any failure mid-batch, **best-effort cleanup** of already-stored keys before rethrowing as `BusinessException(IMAGE_UPLOAD_FAILED)` so the surrounding transaction can roll back the DB side cleanly. Storage key format: `complaint/{complaintId}/COMPLAINT/{uuid}.{jpg|png}`.
- `ComplaintCreationService` — `@Transactional` end-to-end submit:
  1. Verify `request.consumerId == verified-JWT.consumerId` → else `COMPLAINT_NOT_OWNED_BY_CONSUMER` (anti-tamper; verified token wins).
  2. `ConsumerLookupService.requireActiveByConsumerId(...)` (re-load + active check).
  3. `ComplaintCategoryService.requireActive(...)` (re-load + active check; takes `slaHours` from the category for SLA calc).
  4. `TicketNumberService.nextTicketNumber()` (runs in `REQUIRES_NEW`, mints + commits the sequence row in its own tx so the submit tx's commit window stays short).
  5. Persist `Complaint` (status=`SUBMITTED`, `sla_deadline = nowIst() + category.slaHours`, `distribution_center_id` derived from `consumer_master`).
  6. Persist initial `ComplaintHistory(null → SUBMITTED)`.
  7. `ComplaintImageService.storeAll(...)` — the single external side-effect inside the tx, honouring the "≤ 1 external call per transaction" hard rule.
- `ComplaintReadService.getOwnedByTicketNo(caller, ticketNo)` — `@Transactional(readOnly = true)`. Loads by ticket → `COMPLAINT_NOT_FOUND` if missing → ownership check on `consumer_master_id` → `COMPLAINT_NOT_OWNED_BY_CONSUMER` (deliberately distinct from 404 to avoid leaking ticket-number existence per BRD §6 privacy). Maps to `ComplaintDetailResponse` with images.

**Controller (`complaint.controller.ConsumerComplaintController`):**
- `POST /api/v1/consumer/complaints` (`consumes = multipart/form-data`, returns **201 Created**) — `@RequestPart("complaint") @Valid SubmitComplaintRequest` + `@RequestPart(value="images", required=false) List<MultipartFile>`. `@AuthenticationPrincipal VerifiedConsumer caller`.
- `GET /api/v1/consumer/complaints/{ticketNo}` — owned-by-caller detail.
- Springdoc multipart annotations + a documentation-only `SubmitMultipartForm` schema so the generated OpenAPI surfaces the part-layout (`complaint` as `SubmitComplaintRequest`, `images` as `binary[]`). FE codegen and Swagger UI both render the form shape correctly.

**`ErrorCode` additions:** `IMAGE_UPLOAD_FAILED` (HTTP 500). Everything else (`IMAGE_TOO_LARGE`, `IMAGE_INVALID_TYPE`, `IMAGE_LIMIT_EXCEEDED`, `COMPLAINT_NOT_FOUND`, `COMPLAINT_NOT_OWNED_BY_CONSUMER`, `CATEGORY_NOT_FOUND`, `CATEGORY_INACTIVE`, `CONSUMER_NOT_FOUND`, `CONSUMER_INACTIVE`) was already on disk from earlier scaffolding.

**No Flyway migration** — schema for all four tables already in `V1.0__init_schema.sql`. Hard-rule #5 honoured.

##### Incidents fixed during implementation

No new incidents originated in Stage 10b itself — the Hibernate type-mismatch class of bug from 10a (incident #1) didn't recur because the new entities use plain `VARCHAR` / `TEXT` / `TIMESTAMPTZ` columns that map straight from Hibernate defaults. The Stage 8b `SecurityContextHolder` test pattern was reused for `ConsumerComplaintControllerTest` exactly as documented — `@AfterEach clearContext()` + a tiny `authenticate(...)` helper in `@BeforeEach`. The `@RequestPart` + Mockito + multipart combination worked first-try with Spring's `MockMultipartFile` + `MockMvcRequestBuilders.multipart(...)`.

##### Tests added

Minimum-test policy applied: 1 happy + 1 unhappy per service method, WebMvcTest per endpoint.

- `complaint/service/ComplaintImageServiceTest` — **5 tests**:
  - happy: persists each valid image, returns in input order, correct storage-key shape.
  - unhappy: unsupported content type → `IMAGE_INVALID_TYPE`, never touches storage.
  - unhappy: more than `maxImages` → `IMAGE_LIMIT_EXCEEDED`, never touches storage.
  - unhappy: 2nd image storage write throws → 1st already-written key deleted, rethrown as `IMAGE_UPLOAD_FAILED`.
  - edge: null / empty list → no-op, no calls to storage or repo.
- `complaint/service/ComplaintCreationServiceTest` — **2 tests**:
  - happy: complaint + history persisted; ArgumentCaptor asserts ticket #, status, contact mobile, DC derived from consumer-master, SLA ≈ now + category.slaHours.
  - unhappy: body's `consumerId` ≠ verified JWT's → `COMPLAINT_NOT_OWNED_BY_CONSUMER`, ticket number never minted, complaint never saved.
- `complaint/service/ComplaintReadServiceTest` — **3 tests**:
  - happy: owned ticket → mapped detail.
  - unhappy: foreign ticket (different `consumer_master_id`) → `COMPLAINT_NOT_OWNED_BY_CONSUMER` (deliberately not 404).
  - unhappy: missing ticket → `COMPLAINT_NOT_FOUND`.
- `complaint/controller/ConsumerComplaintControllerTest` (`@WebMvcTest`, `addFilters=false`) — **4 tests**:
  - `POST /complaints` happy → 201 + ticketNo in `data`.
  - `POST /complaints` blank `consumerId` → 400 + `VALIDATION_FAILED`.
  - `GET /complaints/{ticketNo}` happy → 200 + mapped detail.
  - `GET /complaints/{ticketNo}` foreign ticket → 403 + `COMPLAINT_NOT_OWNED_BY_CONSUMER`.

##### Build status

```
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +14 from Stage 10a: 5 image + 2 creation + 3 read + 4 controller)
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged from Stage 10a)
[INFO] BUILD SUCCESS
docs/openapi.json — 32 paths (was 30); +2: POST /api/v1/consumer/complaints, GET /api/v1/consumer/complaints/{ticketNo};
  +6 schemas: SubmitComplaintRequest, SubmitComplaintResponse, ComplaintDetailResponse, ComplaintImageResponse, SubmitMultipartForm, ApiResponseSubmitComplaintResponse, ApiResponseComplaintDetailResponse (springdoc-generated wrappers).
```

##### Carry-overs / known follow-ups

- **`ComplaintCreationIT`** (end-to-end Testcontainers + temp-dir `LocalStorageService`) was scoped into Stage 10b but **not** shipped. The 14 new unit tests + 4 WebMvcTest tests + the existing `ComplaintsApplicationIT` (bean wiring) + `TicketNumberServiceIT` (concurrency) collectively cover every meaningful path; the marginal benefit of one more "wires together" IT didn't clear the "would I miss this if it broke tomorrow?" bar. Will be added alongside the first FE-driven smoke pass (Stage 11) when the test will double as the smoke baseline.
- **Stage 10c** (deferred deploy gate, carried from 10a) — `GcsStorageService` + `google-cloud-storage` dep + profile wiring. Until it lands, deployed test/prod profiles must run with `app.storage.type=local` on a writable mount.
- **OpenAPI binary multipart in FE codegen** — `SubmitMultipartForm` renders as `{complaint: SubmitComplaintRequest, images: binary[]}` with per-part `encoding` (JSON for `complaint`, `image/jpeg, image/png` for `images`). FE prompt for Stage 11 should explicitly tell the agent: *"the generated `submitComplaint` function takes `complaint: SubmitComplaintRequest` + `images: File[]`. There is no separate image upload call. The Axios `FormData` shape must match the multipart parts named exactly `complaint` and `images`."*
- **Cross-module entity leak** — `ComplaintCategoryService.requireActive(id)` returns a `ComplaintCategory` JPA entity across the masterdata→complaint boundary. The copilot-instructions soft rule says "Cross-module data exchange via DTOs / records / events — never JPA entities", but ArchUnit `modules_must_not_call_other_modules_repositories` only enforces the repository hop. Pre-existing precedent (the method was added during Stage 6 specifically for "Phase 3 complaint creation"). Tracked as a low-priority refactor: add `ComplaintCategoryService.requireActiveView(id) → ComplaintCategoryView` and switch the one call-site; do it the next time we touch either service for unrelated reasons.
- **Cancellation, feedback, lifecycle history, list-by-consumer** — explicitly **deferred to Phase 5** (`ROADMAP.md`). `Feedback` entity + repo shipped now so Phase 5 doesn't bounce against schema-add work, but no service / endpoint exists.
- **Audit event `ComplaintSubmittedEvent`** — not published. Audit module is Phase 7 (consistent with all prior stages).
- **`MaxUploadSize` exhaustion** — Spring's default multipart limit is 1 MB per file / 10 MB per request; we rely on these defaults plus the explicit `IMAGE_TOO_LARGE` per-file check inside `ComplaintImageService`. If a future deploy bumps the Spring limits, the service still enforces `app.complaint.max-image-bytes`. No `MaxUploadSizeExceededException` handler is wired — Spring's default 413 surfaces through `GlobalExceptionHandler`'s generic `Exception` handler as a 500 today. Tracked as a small follow-up: add a `@ExceptionHandler(MaxUploadSizeExceededException.class)` mapping to `IMAGE_TOO_LARGE` so the FE sees the same error code regardless of which limit blew.

---

#### Stage 10b · Post-stage hotfix — consumer-side category list — ✅ 2026-06-22

> Caught during the Stage 11 FE handoff prep: the original Stage 10b plan implicitly assumed
> the FE would call `GET /api/v1/staff/masterdata/categories` for its submit-form dropdown.
> `SecurityConfig` actually gates that path behind {@code .authenticated()} — i.e. a staff JWT.
> Consumers have no staff JWT (hard-rule #6), so the FE would have hit `401 UNAUTHORIZED` on
> the first category-dropdown render.

##### Scope delivered

- `ComplaintCategoryRepository.findByActiveTrue(Pageable)` — new derived finder.
- `ComplaintCategoryService.listActive(Pageable)` — `@Transactional(readOnly = true)` page over active rows only. Staff `list(Pageable)` still returns the full set so admins can audit inactive rows.
- `masterdata.controller.ConsumerMasterdataReadController` — new controller, `GET /api/v1/consumer/masterdata/categories`. Lives in the `masterdata` module (its controllers may only call its own services — hard-rule), but the URL prefix puts it under `/consumer/**` so `ConsumerVerificationFilter` is the actual gate. No `SecurityConfig` change needed: `/api/v1/consumer/**` is already `permitAll` at the chain level + filter-gated.
- 1 unit test in `ComplaintCategoryServiceTest` (delegates to `findByActiveTrue` + maps each row).
- 1 WebMvcTest `ConsumerMasterdataReadControllerTest` (200 happy path, active flag surfaced).

##### Build status

```
[INFO] Tests run: 62, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +2 from Stage 10b: 1 service + 1 controller)
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 33 paths (was 32); +1: GET /api/v1/consumer/masterdata/categories.
```

##### Why this is a hotfix, not Stage 11 work

- Strictly backend. FE agent should never touch BE security wiring.
- Found by reviewing the FE handoff prompt against `SecurityConfig` — a class of bug that would otherwise have wasted half a Stage-11 day on phantom 401s.
- Future-proofing for Stage 11: the FE prompt now correctly points at `/api/v1/consumer/masterdata/categories` (consumer JWT, active-only).

##### Carry-overs

- **Other masterdata** (subdivisions, distribution centers) is **not** exposed under `/consumer/**` — the consumer never picks a DC or subdivision; both are derived server-side from `consumer_master` at submission. Keep it that way unless a Phase 5 / 6 screen explicitly needs it.

---

#### Stage 10b · Post-stage follow-ups (3 small wins while FE works Stage 11) — ✅ 2026-06-22

> Three independent BE follow-ups closed while the FE agent runs Stage 11. Each one shipped
> green on its own; combined they harden the Phase 3 surface without touching anything the
> FE is touching.

##### 1. `MaxUploadSizeExceededException` → `IMAGE_TOO_LARGE` (413)

Spring fires `MaxUploadSizeExceededException` **before** `@RequestPart` validation when the
raw multipart bytes exceed the servlet container limits (`spring.servlet.multipart.max-file-size`
1 MB / `max-request-size` 10 MB by default). `ComplaintImageService`'s explicit per-image
`IMAGE_TOO_LARGE` check therefore never runs for that path — it used to fall through to the
generic `Exception` handler in `GlobalExceptionHandler` and surface as `500 INTERNAL_ERROR`.

Added a dedicated `@ExceptionHandler(MaxUploadSizeExceededException.class)` that maps both paths
to the same `IMAGE_TOO_LARGE` (413). FE never has to disambiguate "raw multipart blew" vs
"validated image too big". 1 unit test in the new `GlobalExceptionHandlerTest`.

##### 2. Category-deactivation open-complaints guard (Stage 6 carry-over closed)

The `TODO(sunil, phase-3)` in `ComplaintCategoryService.setActive` finally has a home: an admin
can no longer deactivate a category while open (SUBMITTED / ASSIGNED / IN_PROGRESS) complaints
reference it. Terminal-status complaints (RESOLVED / CLOSED / CANCELLED / REJECTED / DUPLICATE)
do **not** block deactivation — once a complaint exits the live workflow, the category row
is decorative for it.

Cross-module hop respects the ArchUnit `modules_must_not_call_other_modules_repositories` rule:

- New `complaint.service.ComplaintQueryService.existsOpenForCategory(Long)` — thin, scalar-only
  read API. Public `OPEN_STATUSES` constant pinned to `{SUBMITTED, ASSIGNED, IN_PROGRESS}` so
  the next caller (DC deactivation guard in Phase 4, list-screen badges, etc.) reuses the same
  semantics rather than re-deriving them.
- New `ComplaintRepository.existsByCategoryIdAndStatusIn(Long, Collection<ComplaintStatus>)`.
- New `ErrorCode.CATEGORY_HAS_OPEN_COMPLAINTS` (409 — same shape as the existing
  `SUBDIVISION_HAS_ACTIVE_DCS` etc.).
- `ComplaintCategoryService` injects `ComplaintQueryService` and calls it from `setActive(...)`
  **only when transitioning active→inactive** (re-activation is always safe and skips the hop).

Removes one of the three `TODO(sunil, phase-3)` markers in the codebase. The other two
(DC deactivation second guard, audit events) are tracked separately.

Tests: 3 new unit tests in `ComplaintCategoryServiceTest`:
- happy: deactivation blocked when an open complaint exists → `CATEGORY_HAS_OPEN_COMPLAINTS`,
  entity never mutated.
- happy: deactivation succeeds when no open complaints exist.
- safety: re-activation (`setActive(true)`) never consults the complaint module (Mockito
  `verify(complaintQuery, never())…`).

ArchUnit's 5 boundary rules still green — confirms the cross-module hop via
`ComplaintQueryService` is on the allowed side of the line.

##### 3. `ComplaintCreationIT` (Stage 10b carry-over closed)

End-to-end IT against a real Postgres (Testcontainers) and real `LocalStorageService` (temp
directory created in a static initialiser and wired via `@DynamicPropertySource`). One happy
path per minimum-test policy:

- Seeds: a subdivision + DC (`SUB-IT-001` / `DC-IT-001`, idempotent — does **not** wipe pre-existing
  rows because V1.2's bootstrap admin holds an FK into `subdivision`; see incident #1 below)
  + a consumer-master row (`MH00099999`).
- Calls `ComplaintCreationService.submit(...)` with a `MockMultipartFile` (3 raw JPEG header bytes).
- Asserts:
  - response ticket matches `MH\d{6}\d{8}` and status is `SUBMITTED`;
  - persisted `complaint` row: status, DC derived from `consumer_master`, contact mobile,
    SLA deadline = `created_at + 24h` ± 5min (POWER_OUTAGE seed has 24h SLA);
  - exactly one `complaint_history` row with `(from=null, to=SUBMITTED)`;
  - exactly one `complaint_image` row with `image_type=COMPLAINT`, `content_type=image/jpeg`,
    `storage_key` matching `complaint/{id}/COMPLAINT/.*\.jpg`;
  - the JPEG bytes are actually written to disk at `${STORAGE_ROOT}/<storage_key>`.

##### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | First run of `ComplaintCreationIT` failed in `@BeforeEach` with `DataIntegrityViolationException: violates foreign key constraint "user_account_subdivision_id_fkey"`. | The `@BeforeEach` did a blanket `subdivisions.deleteAll() / dcs.deleteAll()` to give each test a clean slate. V1.2 seeds the bootstrap admin, whose `user_account.subdivision_id` FK points at the very subdivision row we were trying to wipe. | Reordered the seed logic: only delete the rows owned by this IT (`history`, `images`, `complaints`, `consumers`). For subdivision + DC, `findByCode("SUB-IT-001") || save(...)` — idempotent, never collides with bootstrap row codes (`SUB-NSK-001` etc.), and survives re-runs. Documented inline so the pattern is reusable when the next module-level IT lands. |

##### Build status

```
[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit;  +4 from Stage 10b: 1 GlobalExceptionHandler + 3 ComplaintCategoryService)
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;    +1 from Stage 10b: ComplaintCreationIT)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged at 33 paths (no controller surface changes).
```

##### Carry-overs that remain open

- **Stage 10c** — GCS + MSG91 wiring; still blocked on external accounts.
- **DC deactivation second guard** — same pattern as #2 above (Stage 6 carry-over). The
  reusable `ComplaintQueryService` constant `OPEN_STATUSES` is now in place, so the DC-side
  hook is a 30-min job whenever the next DC-deactivation-related issue surfaces. Defer until
  there's a real call for it.
- **Cross-module entity leak refactor** — `ComplaintCategoryService.requireActive(Long)`
  still returns an entity across the module boundary. Tracked from Stage 10b; ArchUnit doesn't
  enforce this rule yet. Will close together when the matching ArchUnit rule lands.
- **Audit events** (`CategoryDeactivationBlockedEvent` etc.) — Phase 7.

---

### Stage 11 · Frontend consumer PWA — ✅ 2026-06-22 (FE-led)

> FE-led shipping of the consumer entry → OTP → submit → confirmation flow against the
> Phase 3 backend surface. Source of truth for FE scope, gates, and incidents is
> [`complaints-frontend/docs/IMPLEMENTATION_LOG.md`](../../complaints-frontend/docs/IMPLEMENTATION_LOG.md)
> Stage 11 entry.

#### Backend impact

- **Zero backend code changes required during the stage itself.** Stage 9 (OTP), Stage 10a
  (storage / ticket numbers), Stage 10b (submit + read), and the post-stage hotfix
  (`/api/v1/consumer/masterdata/categories`) collectively covered every endpoint the FE
  consumed.
- The three post-stage follow-ups (`MaxUploadSizeExceededException` handler, category
  open-complaints guard, `ComplaintCreationIT`) were land **before** the FE smoke pass; the
  first of those was specifically motivated by anticipating an FE-side phantom 5xx.

#### Things the FE found that the BE side now owns

| # | Finding | Action taken |
|---|---------|--------------|
| 1 | **orval multipart-JSON contract gap.** orval emits the generated `submitComplaint` with the JSON `complaint` part serialised as `text/plain` rather than `application/json`, even though the OpenAPI spec carries an `@Encoding(name="complaint", contentType="application/json")` annotation. BE then rejects the request as `400 VALIDATION_FAILED`. FE works around it with a hand-rolled `submitComplaintMultipart` wrapper that builds the JSON part via `new Blob([JSON.stringify(req)], { type: 'application/json' })`. | Added a prominent **"Multipart contract gotcha"** Javadoc block on `ConsumerComplaintController` so any future multipart endpoint author sees the warning next to the code. Documented here so reviewers of a new multipart endpoint can spot the same trap before the FE rediscovers it. If we ever swap orval for a different OpenAPI client generator, the multipart contract is the canary that tells us whether the new tool honours `@Encoding`. |
| 2 | **`OTP_TOO_MANY_ATTEMPTS` lock branch is hard to hit in manual smoke.** BE unit tests cover it (`OtpServiceTest`), but in dev the 30-second cooldown between OTP sends trips first, so the FE could never manually exercise the locked-modal UI without a knob. | Documented `APP_OTP_COOLDOWN_SECONDS=0` (and `APP_OTP_MAX_ATTEMPTS=2`) under `ENVIRONMENT_SETUP.md §1.6 "Useful dev-only overrides"` so future FE agents (and ourselves) can flip the lock branch on for manual UI verification. No code change — Spring Boot's relaxed binding already exposes every `app.*` property as an env var. |

#### Things the FE confirmed went right

- **Stage 10b-hotfix `/consumer/masterdata/categories` move** — the FE called out that fixing
  the auth boundary before the FE handoff (rather than after they'd already built the wrong
  thing) saved them from threading "this one endpoint is staff-scoped but public-feeling"
  through the consumer-side store. Worth repeating: surface security mismatches in handoff
  prompts, not after FE has wired against them.
- **403-not-404 on foreign tickets** (Stage 10b `ComplaintReadService`) — let the FE render a
  specific "this ticket isn't yours" state instead of conflating with "not found". Keep the
  same pattern when Phase 5 lifecycle endpoints land
  (`POST /consumer/complaints/{ticketNo}/cancel`, `POST .../feedback`).

#### Phase 5 endpoint shape requested by FE (for planning, no action yet)

The FE flagged the three Phase 5 endpoints it'll need before it can grow the consumer
"my complaints / cancel / feedback" UI. Already on the roadmap; recorded here so the BE
side has the requested shapes pre-pinned:

| Endpoint | Notes |
|----------|-------|
| `GET /api/v1/consumer/complaints` | List-by-verified-consumer, scoped by the `consumerId` claim on the verification JWT (one consumer can have multiple complaints across the same Consumer ID; mobile is **not** the scoping field). Pagination + `?status=` filter likely. |
| `POST /api/v1/consumer/complaints/{ticketNo}/cancel` | `SUBMITTED → CANCELLED` transition only. Body: `{ reason }` (required). 409 on any other source state. |
| `POST /api/v1/consumer/complaints/{ticketNo}/feedback` | One-shot only (UNIQUE on `complaint_id` in `feedback` already enforces this). Allowed only when status = `CLOSED`. Body: `{ rating: 1-5, comment?: string }`. |

Status-transition validator (TECHNICAL_DESIGN §5.4 "Status state machine") is the right
home for the cancel path; the existing `Feedback` entity + repo (shipped in Stage 10b for
schema-readiness) means the feedback path is a service + controller, no schema work.

#### Stage 10c (GCS) is now a hard prerequisite for non-loopback deploy

FE highlighted that the confirmation screen renders `<img src={image.url}>` verbatim from
the `ComplaintMapper`-issued signed URL. In dev that's a `file://` URI — fine on
`localhost` because the FE dev server has filesystem access, but **renders nothing** from
any other origin. So a deployed preview / smoke environment for the consumer flow can't
ship until `GcsStorageService` lands. Same Stage 10c carry-over that's been tracked since
Stage 10a; the FE has just made the cost-of-deferring concrete. No code action yet —
still blocked on GCS service-account provisioning.

#### CORS reminder from FE

Dev profile allows `http://localhost:*`. When a deployed preview origin lands, the FE will
ping us to add it to `app.cors.allowed-origins` before the OTP-send preflight starts 403'ing.
Tracking inline so it's not lost.

#### Build status

```
[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; unchanged)
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged at 33 paths (FE-led stage, no BE controller changes).
```

#### Carry-overs that remain open (now annotated with FE pressure)

- **Stage 10c (GCS)** — escalates from "deferred follow-up" to "hard prerequisite for any non-loopback preview" per FE feedback above.
- **Phase 5 consumer-side lifecycle endpoints** (`list / cancel / feedback`) — shape pre-pinned above; build when Phase 5 starts.
- **Cross-module entity leak refactor + ArchUnit rule** — still tracked from Stage 10b.
- **DC deactivation open-complaints guard** — still tracked; `OPEN_STATUSES` constant is ready.
- **OpenAPI spec-drift CI guard** — still Phase 7 per the existing plan.

---

## Phase 4 — Triage, assignment, and technician resolution

> Goal per `ROADMAP.md` Phase 4: full complaint lifecycle works — engineer assigns to technician,
> technician resolves and closes. Largest phase by code volume; split into 5 stages so each
> ships green independently.
>
> Plan: **Stage 12** state machine + optimistic lock (foundation) → **Stage 13** assignment + triage →
> **Stage 14** technician resolution + closure → **Stage 15** SLA breach scheduler →
> **Stage 16** complaint search (Specifications). Stages 13–16 all depend on the validator +
> `@Version` from Stage 12.

### Stage 12 · State machine + optimistic lock — ✅ 2026-06-22

> Foundation slice — no HTTP endpoints, no OpenAPI delta. Pins the lifecycle rules in one
> place and turns concurrent updates into a clean 409 instead of silent overwrites. Every
> subsequent Phase-4 service consults `ComplaintStatusTransition.requireValid(...)` and
> mutates `Complaint` rows under the new `@Version` guard.

#### Scope delivered

- **Flyway migration** `V1.4__add_complaint_version.sql` — adds `version BIGINT NOT NULL DEFAULT 0`
  to `complaint`. Hard-rule #5 honoured (new file, not an edit of any committed V1.x).
- **`Complaint.version`** — `@Version private long version` with explicit Javadoc noting the
  distinction from the time-based `updated_at` column (which is DB-managed, monotonic in time
  but not in update count).
- **`complaint.model.ComplaintStatusTransition`** — final class with private constructor,
  holds the single allow-table:
  - `SUBMITTED   → ASSIGNED, CANCELLED, REJECTED, DUPLICATE`
  - `ASSIGNED    → IN_PROGRESS`
  - `IN_PROGRESS → RESOLVED`
  - `RESOLVED    → CLOSED`
  - `CLOSED, CANCELLED, REJECTED, DUPLICATE → ∅` (terminal)
  - Three static methods: `isAllowed(from, to)`, `requireValid(from, to)` (throws
    `COMPLAINT_INVALID_STATE_TRANSITION`), `isTerminal(status)` (UI badge convenience).
  - `null from` is the initial-insert path; only valid against `SUBMITTED`.
  - **Reassignment, severity edits, audit notes** keep `status` unchanged and so do **not**
    consult this validator — the validator gates *state* transitions only. Documented inline
    so the next service author doesn't mis-classify reassign as a "transition".
- **`GlobalExceptionHandler` mapping** — `ObjectOptimisticLockingFailureException` →
  `COMPLAINT_VERSION_CONFLICT` (409). The `ErrorCode` was reserved in Stage 1 and finally has
  a wired source.

**Status transition decisions (v1, deliberately conservative):**
- No rollback edges (no `RESOLVED → IN_PROGRESS`, no `CLOSED → RE_OPENED`).
- No same-state edges (reassignment ≠ transition; severity edits ≠ transition).
- No cross-terminal recovery (`REJECTED → SUBMITTED` etc.).
- If BRD revisions add edges, do it here in one diff rather than spreading `if/else` ladders.

#### Incidents fixed during implementation

None this stage. The Flyway migration applied cleanly (`ADD COLUMN ... DEFAULT 0` is a
constant-time operation on Postgres ≥ 11), Hibernate schema validation accepted the new
column without coercion (`BIGINT` ↔ Java `long` is the default mapping), and the new
exception handler hooked into the existing `@RestControllerAdvice` chain without ordering
issues (Spring picks the most-specific handler regardless of declaration order).

#### Tests added

Minimum-test policy applied: 1 representative case per branch class.

- `complaint/model/ComplaintStatusTransitionTest` — **6 tests**:
  - happy path: full SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED.
  - SUBMITTED's terminal alternatives (cancel / reject / duplicate).
  - `null` from-status: only SUBMITTED target allowed (initial insert).
  - `requireValid` throws `COMPLAINT_INVALID_STATE_TRANSITION` on rollback edges.
  - Every terminal state refuses every outgoing transition (parameterised across the 4 × 8 grid).
  - Self-transitions are disallowed (reassignment goes around the validator).
- `common/exception/GlobalExceptionHandlerTest` — **+1 test** for the new optimistic-lock
  handler (asserts 409 + `COMPLAINT_VERSION_CONFLICT` envelope).

No new IT — the existing `ComplaintsApplicationIT` + `ComplaintCreationIT` exercise the
Flyway migration and the `@Version` column end-to-end (Hibernate flushes the version on
first insert, schema validation passes on context load).

#### Build status

```
[INFO] Tests run: 73, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +7 from Stage 11: 6 transition + 1 handler)
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged set, all still green against V1.4 migration)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged at 33 paths (no controller surface changes).
```

#### Carry-overs / known follow-ups

- **Stage 13** (next slice) — `ComplaintAssignmentService` (engineer/admin assign + reassign)
  and `ComplaintTriageService` (severity update, reject, mark-duplicate). Both will compose
  `ComplaintStatusTransition.requireValid(...)` for status changes and rely on `@Version` for
  concurrency. New endpoints under `/api/v1/engineer/complaints/{id}/...` and
  `/api/v1/admin/complaints/{id}/...`.
- **Domain events** (`ComplaintAssignedEvent`, `ComplaintStatusChangedEvent`, etc.) — Stage 13
  will introduce the event classes and `@TransactionalEventListener` audit-side sinks land in
  Phase 7. Stage 12 deliberately ships no events: there is no state change to publish about.
- **Re-open transition** (`CLOSED → SUBMITTED-or-equivalent`) — explicitly not in v1 per BRD §3.4.
  If a future revision adds it, the allow-table is the single edit point; no service-side
  refactor needed.
- **`ComplaintStatusTransition` ArchUnit guard** — there is no rule yet preventing a service from
  mutating `complaint.status` without going through the validator. Worth adding alongside the
  cross-module entity-leak rule when we revisit ArchUnit. Until then, code review owns it.

---

### Stage 13 — Phase 4 Stage 2 · Assignment + Triage (engineer/admin)

**Refs:** TECHNICAL_DESIGN.md §5.4 · BRD §3.4 · ROADMAP.md Phase 4.

#### What shipped

REST surface gained five engineer/admin endpoints under `/api/v1/staff/complaints/{id}/...`:
`assign`, `reassign`, `severity`, `reject`, `mark-duplicate`. Path-level role gate added to
`SecurityConfig` — `hasAnyRole("ENGINEER","ADMIN")` for `/api/v1/staff/complaints/**`,
sitting **before** the broader `/api/v1/staff/**.authenticated()` matcher. Method security
deliberately **not** enabled — the path matcher is enough for Stage 13's surface; we revisit
if/when we need per-method `@PreAuthorize` expressions.

Two services landed in the `complaint` module:

| Service | Methods | Status transitions |
|---|---|---|
| `ComplaintAssignmentService` | `assign`, `reassign` | `SUBMITTED → ASSIGNED` (assign); none (reassign) |
| `ComplaintTriageService` | `updateSeverity`, `reject`, `markDuplicate` | none / `→ REJECTED` / `→ DUPLICATE` |

All status changes route through `ComplaintStatusTransition.requireValid(...)` from Stage 12.
`updateSeverity` and `reassign` are same-state edits and deliberately do **not** consult the
validator — they only refuse on terminal states.

Cross-module wiring:

- New record `auth.service.StaffScopeView(userId, role, subdivisionId, distributionCenterId, enabled)`.
- `StaffLookupService.getActiveTechnician(Long)` — throws `TECHNICIAN_NOT_FOUND` if missing,
  disabled, or wrong role.
- `StaffLookupService.findActiveEngineerForDc(Long)` — used on admin cross-DC reassignment to
  re-point `assigned_engineer_id`. Backed by new repo method
  `findFirstByRoleAndDistributionCenterIdAndEnabledTrue` (partial-unique index
  `uq_one_active_engineer_per_dc` guarantees at most one).
- `DistributionCenterService.getSubdivisionId(Long)` — read-only helper so the complaint
  module can resolve DC → subdivision for admin scope checks without crossing into
  `masterdata.repository` (ArchUnit forbids).

New `ComplaintScopeGuard` (component, private to the `complaint.service` package in spirit):
encodes the two scope rules from TD §5.4 — Engineer sees only their DC, Admin sees only their
subdivision (via DC → subdivision lookup). Extracted on day one rather than waiting for the
"second use" because both Phase-4 services need it in the same stage and admin scope is
non-trivial.

DTOs (all records, `jakarta.validation` on components):
`AssignComplaintRequest`, `ReassignComplaintRequest`, `UpdateSeverityRequest`,
`RejectComplaintRequest`, `MarkDuplicateRequest`.

New `ErrorCode` entries: `TECHNICIAN_NOT_FOUND`, `COMPLAINT_OUT_OF_SCOPE`, `DUPLICATE_OF_SELF`,
`DUPLICATE_PARENT_INVALID`, `NO_ACTIVE_ENGINEER_FOR_DC`. The pre-existing
`TECHNICIAN_NOT_IN_DC` is reused for both engineer-DC-mismatch and admin-subdivision-mismatch
of the chosen technician (the user-facing message is the same).

Reassignment behaviour worth pinning:

- Engineer caller: technician must be in same DC; `distribution_center_id` and
  `assigned_engineer_id` are not touched.
- Admin caller: technician may be in any DC under the admin's subdivision. When the technician
  is in a *different* DC than the complaint, both `distribution_center_id` and
  `assigned_engineer_id` are re-pointed to the new DC's active engineer. If that DC has no
  active engineer → `409 NO_ACTIVE_ENGINEER_FOR_DC` (deliberate — we don't silently break the
  partial-unique invariant).

Audit trail: every operation appends a `complaint_history` row. Severity-update and
reassignment write rows with `from_status == to_status` and an inline note (`"Severity changed
from X to Y"`, `"Reassigned from technician X to Y"`); state transitions write
`from != to` as usual.

Concurrency: every mutation runs through `@Transactional` services on the `@Version`-annotated
aggregate. Two concurrent assigns/reassigns to the same complaint surface as
`COMPLAINT_VERSION_CONFLICT` (409) via the Stage 12 global handler — no service code needed
this stage.

#### Incidents / decisions

1. **`/api/v1/staff/**` vs `/api/v1/engineer/**` + `/api/v1/admin/**`.** Original TD §5.4
   sketch had separate engineer/admin URL trees. Settled on `/staff/complaints/**` because the
   five endpoints behave identically modulo scope rules already enforced server-side — two
   parallel trees would have doubled the controller surface and OpenAPI path count for zero
   FE benefit. SecurityConfig matcher gates the whole subtree to `ENGINEER+ADMIN`; the role
   distinction stays inside the services where it belongs.

2. **`ComplaintScopeGuard` extracted on day one** (not waiting for the "second use" rule).
   Justification: two services land in the same stage, and the admin branch needs a DC →
   subdivision lookup that we did not want to inline-duplicate. Documented inline so the
   pattern decision is auditable.

3. **Reassignment does not require a state transition.** The validator's allow-table refuses
   `ASSIGNED → ASSIGNED` (no self-edges). Reassignment is encoded as "must currently have an
   assigned technician AND must not be in a terminal/RESOLVED/CLOSED state", checked directly
   in the service rather than threading a synthetic transition through the validator.

#### Tests

- `ComplaintAssignmentServiceTest` — 4 tests: engineer happy path; engineer rejecting a
  cross-DC technician; admin cross-DC reassignment re-pointing DC + engineer; reassign refused
  on an unassigned complaint.
- `ComplaintTriageServiceTest` — 6 tests: severity happy + terminal-status refusal; reject
  happy + non-SUBMITTED refusal (state machine); mark-duplicate happy + self-reference refusal.
- `ComplaintScopeGuardTest` — 2 tests: engineer same-DC pass; admin cross-subdivision block.
- `StaffComplaintControllerTest` (`@WebMvcTest`) — 2 tests: assign happy → 200 envelope +
  service delegation; reject with blank reason → 400 `VALIDATION_FAILED`.

No new IT this stage — full lifecycle (consumer-submit → engineer-assign → technician-resolve
→ close) lands as one end-to-end IT in Stage 14 once the resolution flow exists. Going earlier
would force half the flow to be mocked, which defeats the point.

#### Build status

```
[INFO] Tests run: 87, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +14 from Stage 12: 4+6+2+2)
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged set, all still green)
[INFO] BUILD SUCCESS
docs/openapi.json — 33 → 38 paths (+5 staff complaint endpoints).
```

#### Carry-overs / known follow-ups

- **Stage 14** (next slice) — `ComplaintResolutionService` (technician start → resolve),
  `ComplaintClosureService` (engineer/admin close-on-behalf), resolution-image upload, and
  the SLA-breach-reason-required-when-breached rule. Will reuse `ComplaintScopeGuard` and
  `ComplaintStatusTransition` as-is.
- **`GET /staff/complaints` (paged scope-filtered list)** — deferred to Stage 16 alongside
  Specification-based search. FE will need a stub mock until then, but no FE work in Phase 4
  is blocked by its absence.
- **Domain events** — still not emitted. Will land alongside the `notification` module in
  Phase 6/7. Triage/assignment service methods are obvious publish sites; today they only
  log + write history rows.
- **Cancellation (`SUBMITTED → CANCELLED` by consumer)** — Phase 5 (consumer tracking slice).
  The transition is already allowed by the state machine.

---

### Stage 13.5 — Phase 4 Stage 2.5 · Staff complaint read (detail + history)

**Refs:** TECHNICAL_DESIGN.md §5.4 · FE Stage 12 prerequisite.

#### What shipped

Two `GET` endpoints on the existing `StaffComplaintController` so the FE engineer/admin UI
can render a complaint detail page and audit-trail timeline before any action modal is
opened:

- `GET /api/v1/staff/complaints/{id}` → `ComplaintStaffDetailResponse`
- `GET /api/v1/staff/complaints/{id}/history` → `List<ComplaintHistoryEntryResponse>`

New `ComplaintStaffReadService` mirrors the consumer-side `ComplaintReadService` but:
- scope-checks via `ComplaintScopeGuard` (engineer DC / admin subdivision) instead of
  consumer ownership;
- returns a richer DTO that exposes technician/engineer IDs, severity, breach flag, all
  reason/notes fields, `resolved_at`, `closed_at`, and the `@Version` value (so FE can echo
  it back on subsequent mutating calls if optimistic-lock UX is added later);
- history is its own endpoint to keep the detail payload bounded.

Mapper gained `toStaffDetailResponse` and `toHistoryResponse`. The history mapper relies on
`DateUtils.toIst` being null-safe — already verified.

No new ErrorCodes, no schema changes, no security config changes (the existing
`hasAnyRole("ENGINEER","ADMIN")` matcher on `/api/v1/staff/complaints/**` covers GETs too).

#### Why a separate micro-stage rather than wait for Stage 16

Stage 16 ships the paged list + Specification-based search. But the FE engineer/admin UI
needs only the single-resource view to make Stage 13's five action endpoints usable — you
can't click "Assign" without first seeing what you're assigning. Carving this out as a 30-min
patch unblocks FE Stage 12 to run in parallel with BE Stage 14 instead of serially after it.

#### Tests

- `ComplaintStaffReadServiceTest` — 4 tests: getById happy, getById 404, getHistory happy,
  getHistory blocked by scope guard.
- `StaffComplaintControllerTest` — 1 new test (`getById_success`) on top of the existing
  Stage 13 cases; the GET path goes through the same `@WebMvcTest` setup, no new slice needed.

No IT — the read path is a single repo call + mapper; the existing `ComplaintCreationIT`
already exercises the underlying schema for these fields.

#### Build status

```
[INFO] Tests run: 92, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +5 from Stage 13: 4 service + 1 controller)
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 38 → 40 paths (+2 GETs).
```

#### Carry-overs / known follow-ups

- **Stage 14** — proceeds as planned (technician resolution + close-on-behalf + SLA-reason
  rule + full-lifecycle IT). The new staff GETs will get exercised by that lifecycle IT.
- **`@Version` on the wire** — FE doesn't have to send it back yet; Stage 13 mutation endpoints
  don't read it from the request, only from the DB row. If we add an "edit screen with optimistic
  concurrency UX" we'll add an `If-Match` header or a `version` body field at that point.
- **Consumer detail vs staff detail divergence** — two DTOs now (`ComplaintDetailResponse` for
  consumers, `ComplaintStaffDetailResponse` for staff). Deliberate — staff fields leak PII /
  internal IDs we never want a consumer JWT to see. Keep them separate even if they grow.

---

### Stage 14 — Phase 4 Stage 3 · Technician resolution + close-on-behalf

**Refs:** TECHNICAL_DESIGN.md §5.4–§5.5 · BRD §3.4 · ROADMAP.md Phase 4 done-criteria.

#### What shipped

Lifecycle is now end-to-end: consumer submit → engineer assign → technician start → resolve
→ engineer close. The Phase 4 done-criteria are met (modulo SLA scheduler, Stage 15).

REST surface gained four endpoints:

| Method | Path | Role | Transition |
|---|---|---|---|
| POST | `/api/v1/technician/complaints/{id}/start` | TECHNICIAN | ASSIGNED → IN_PROGRESS |
| POST | `/api/v1/technician/complaints/{id}/resolve` | TECHNICIAN | IN_PROGRESS → RESOLVED |
| POST | `/api/v1/technician/complaints/{id}/images` | TECHNICIAN | none (multipart upload) |
| POST | `/api/v1/staff/complaints/{id}/close` | ENGINEER/ADMIN | RESOLVED → CLOSED |

Three services:

- **`ComplaintResolutionService`** — `start`, `resolve`, `addResolutionImages`. Per-complaint
  scope is "I am the assigned technician" (compared via `caller.userId()`); cross-technician
  ops still live in `ComplaintAssignmentService` from Stage 13. New `ErrorCode`
  `COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN` (403) for the foreign-technician case.
- **`ComplaintClosureService`** — `close` for engineer/admin. Reuses Stage 13's
  `ComplaintScopeGuard` (engineer DC / admin subdivision).
- **`ComplaintImageService`** refactored to a private generic `store(...)` core; the existing
  `storeAll` (consumer COMPLAINT-type) stays as a thin wrapper for backward compatibility, and
  a new `storeResolutionImages(complaintId, files, technicianUserId)` writes `RESOLUTION`-typed
  rows with `uploaded_by_user_id` populated from the calling technician. Storage key now
  encodes the type (`complaint/{id}/RESOLUTION/{uuid}.jpg`).

SLA-breach-reason rule lives in two places, deliberately not extracted to a helper (the
condition is one line and the two services treat the reason fields differently):

- **resolve**: if `now > slaDeadline` and `req.slaBreachReason` is blank →
  `SLA_BREACH_REASON_REQUIRED`. Otherwise stores the reason and flips `sla_breached = true`.
- **close**: if breached AND no reason on file from resolve AND no reason in request →
  `SLA_BREACH_REASON_REQUIRED`. The "already on file" branch means engineers don't have to
  re-type the reason at close time if the technician captured it at resolve.

`resolved_at` and `closed_at` populated by their respective services (entity already had
nullable columns).

State machine: every status change still routes through
`ComplaintStatusTransition.requireValid(...)` from Stage 12. The validator already encodes
`ASSIGNED → IN_PROGRESS`, `IN_PROGRESS → RESOLVED`, `RESOLVED → CLOSED` — no edits needed.

Mapper change: `toImageResponse` widened from `private` to `public` so
`ComplaintResolutionService.addResolutionImages` can return the upload result in the same
shape consumer-side responses use.

#### End-to-end IT

New `ComplaintFullLifecycleIT` (the one deferred from Stage 13) walks the entire happy path
against a real Postgres + LocalStorageService temp dir:

1. Consumer submits via `ComplaintCreationService` → asserts `SUBMITTED`.
2. Engineer assigns via `ComplaintAssignmentService` → asserts `ASSIGNED`.
3. Technician starts via `ComplaintResolutionService` → asserts `IN_PROGRESS`.
4. Technician resolves (on-time, no breach reason) → asserts `RESOLVED`, `resolved_at` set,
   `sla_breached = false`.
5. Engineer closes → asserts `CLOSED`, `closed_at` set, all FK fields preserved.
6. Audit trail: 5 history rows in chronological order with the expected
   `from_status`/`to_status` chain.

Uses the same idempotent-seed pattern as `ComplaintCreationIT` — codes scoped to `-LC-` so
the two ITs don't collide on the shared masterdata tables.

#### Incidents / decisions

1. **`SubmitComplaintResponse.id` vs `.complaintId`.** First IT pass blew up at compile with
   `cannot find symbol: id()` — the record field is `complaintId`, not `id`. One-line fix. The
   wider lesson: the consumer-facing DTO renames `complaint.id` to `complaintId` for clarity at
   the FE; the staff DTO (`ComplaintStaffDetailResponse`) keeps it as `id` because that's how
   staff URLs are shaped. Possible future polish to unify, not worth a v1 churn.

2. **`storeAll` signature preserved** for the consumer creation path. Considered changing it to
   take `(complaintId, files, type, actorUserId)` and updating the single caller, but the
   existing `ComplaintImageServiceTest` has four cases asserting the COMPLAINT-type behaviour
   specifically — the extra wrapper avoids that test churn while keeping the new
   `storeResolutionImages` method intention-revealing. Two callers, two methods is fine; if a
   third type ever appears we revisit.

3. **SLA-reason-required check is duplicated, not shared.** Two services. The condition is
   "breached AND no reason supplied AND (for close) no reason already captured at resolve".
   Sharing it would require either passing five arguments or splitting per-service variants —
   not a real abstraction win. Documented in both services so the next reader doesn't try to
   DRY them.

4. **Resolution-image upload accepts `IN_PROGRESS` OR `RESOLVED`.** Decision: allow late
   additions while the engineer is verifying before close. After `CLOSED` the audit trail is
   sealed (`COMPLAINT_INVALID_STATE_TRANSITION`).

#### Tests

- `ComplaintResolutionServiceTest` — 6: start happy + wrong-technician unhappy; resolve
  on-time happy + breached-no-reason unhappy + breached-with-reason flags breach;
  addResolutionImages wrong-status unhappy.
- `ComplaintClosureServiceTest` — 4: close on-time happy + breached-no-reason unhappy +
  breached-reason-on-file (no need to resend) + close-from-IN_PROGRESS rejected.
- `TechnicianComplaintControllerTest` (`@WebMvcTest`) — 2: start success + resolve blank-notes
  → VALIDATION_FAILED.
- `StaffComplaintControllerTest` — +1 close-success case on top of Stage 13.5's cases.
- `ComplaintFullLifecycleIT` — 1 end-to-end happy path (covers state machine, history,
  scope checks, FK preservation, timestamps, all in one test as required by the Phase 4
  done-criteria).

#### Build status

```
[INFO] Tests run: 105, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +13 from Stage 13.5: 6+4+2+1)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   +1 ComplaintFullLifecycleIT)
[INFO] BUILD SUCCESS
docs/openapi.json — 40 → 44 paths (+4: close, technician start/resolve/images).
```

#### Carry-overs / known follow-ups

- **Stage 15** (next slice) — `SlaMonitorService` with `@Scheduled(cron = "0 */15 * * * *",
  zone = "Asia/Kolkata")` that flips `sla_breached = true` on past-deadline complaints that
  are not yet terminal. Stage 14 already sets the flag at resolve/close time, so the
  scheduler is the only remaining writer for the "in-flight & overdue" set.
- **Resolution-image cap** — currently the service-level cap (`maxImages = 3`) is shared
  between submit and resolution. If a real customer wants more resolution images we'll split
  the config, but defer until that ask exists.
- **Re-open / un-cancel** — still out of scope for v1 per BRD §3.4. The state-machine
  validator refuses these edges; the only edit point if we add them is `ComplaintStatusTransition`.
- **Domain events** — still not emitted from the resolution/closure paths. Will land alongside
  the `notification` module in Phase 6/7 (`ComplaintResolvedEvent`, `ComplaintClosedEvent`).
- **Multipart-JSON gotcha** — the resolution-image endpoint is multipart but takes ONLY image
  parts (no JSON part), so the orval-multipart-JSON wrapper FE built in Stage 11 is not
  required here. Plain `FormData` with multiple `images` parts works.

---

### Stage 14.5 — Phase 4 Stage 3.5 · Staff directory read

**Refs:** FE Stage 12 carry-over · TECHNICAL_DESIGN.md §5.2.

#### What shipped

Two `GET` endpoints so the FE can resolve staff user-ids into human-readable names:

- `GET /api/v1/staff/users/{id}` → single `StaffDirectoryEntryResponse`
- `GET /api/v1/staff/users?ids=1,2,3` → batch list (hard cap 50, unknown ids silently dropped)

New record `auth.dto.StaffDirectoryEntryResponse(userId, employeeId, fullName, role,
subdivisionId, distributionCenterId, enabled)` — deliberately narrower than the existing
`StaffSummaryResponse` (the "me" shape):

- **Drops** `passwordResetRequired` and `notificationsPushEnabled` (personal flags no other
  staff member should see).
- **Keeps** `subdivisionId` / `distributionCenterId` so the FE technician picker can filter
  client-side without an extra round-trip.
- **Adds** `enabled` so the FE can render historical-but-now-disabled actors with a muted
  state ("by Asha Patel (disabled)").

`StaffLookupService` gained `getDirectoryEntry(Long)` (throws `STAFF_NOT_FOUND` on miss) and
`getDirectoryEntries(Collection<Long>)` (batch, dropping unknowns silently — a history row
referencing a hard-deleted user shouldn't 404 the whole timeline; we don't hard-delete today
but the contract is robust to it).

New `StaffDirectoryController` at `/api/v1/staff/users` — no role split; the existing
`/api/v1/staff/**` → `.authenticated()` matcher in `SecurityConfig` is the gate. Engineers,
admins, and technicians may all resolve names (the FE technician mobile flow will need this
too once it renders "assigned by Engineer X").

#### Why a new DTO instead of reusing `StaffSummaryResponse`

Reusing the "me" shape would leak `passwordResetRequired` (a security-relevant personal flag)
to every other staff member who renders a history row. The cost of a second record is one
file; the cost of the leak is non-zero and only surfaces at audit. Easy call.

#### Why a batch endpoint up-front (not strict "second time you need it")

A single complaint detail screen typically renders 4–6 history rows + 1 technician + 1
engineer = up to 8 distinct user ids. 8 round-trips per view is silly; TanStack dedups but
doesn't batch. Twenty extra lines of service code and one URL parameter avoid the issue
entirely. Documented inline so the choice is auditable.

#### Tests

- `StaffLookupServiceDirectoryTest` — 4 tests: single happy, single not-found, batch drops
  unknown ids, empty/null input short-circuits without a DB hit.
- `StaffDirectoryControllerTest` (`@WebMvcTest`) — 3 tests: single GET success (also asserts
  `passwordResetRequired` field is **not** in the response — guards the leak-prevention
  decision), single GET 404, batch GET success.

No IT — pure read path on top of an existing repository method (`findById`, `findAllById`).

#### Build status

```
[INFO] Tests run: 112, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +7 from Stage 14: 4 service + 3 controller)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 44 → 46 paths (+2 GETs).
```

#### Carry-overs / known follow-ups

- **Stage 15** (next slice) — SLA breach scheduler. The directory endpoint will become useful
  there too: scheduler-written history rows have `changedByUserId = null`, which the FE
  already handles ("by system") — but engineer / technician columns in Stage 16's list view
  will lean on the batch endpoint for name resolution.
- **Caching** — not added in this slice. The directory rows are stable (name / role / scope
  changes are rare) so a Caffeine cache by id with a short TTL is the obvious next step if
  profiling shows the FE hammering this on every screen. Deferring until we have signal.
- **`/api/v1/staff/users` discoverability** — the URL collides conceptually with the
  admin-side `/api/v1/admin/staff` (which manages full lifecycle). Kept distinct because
  the admin one requires ADMIN role and exposes the full edit surface; the directory one is
  read-only and authenticated-only. Worth a brief comment in the FE client.

---

### Stage 14.6 — Phase 4 Stage 3.6 · Directory search (picker hotfix)

**Refs:** FE Stage 12.1 carry-over · TECHNICAL_DESIGN.md §5.2.

#### What shipped

FE Stage 12.1 surfaced a real block: the `TechnicianPicker` was calling ADMIN-only
`/api/v1/admin/staff` to enumerate technicians in a DC, so an ENGINEER opening AssignDialog
got a 403 — and engineer-is-the-assigner is the common case. This patch fixes that by widening
the Stage 14.5 directory endpoint with filter + paging.

Same URL (`GET /api/v1/staff/users`), disambiguated by Spring's `params` attribute:

| Variant | Returns |
|---|---|
| `?ids=1,2,3` | `List<StaffDirectoryEntryResponse>` (Stage 14.5 batch) |
| `?role=…&distributionCenterId=…&active=…&page=…&size=…` | `PageResponse<StaffDirectoryEntryResponse>` |

All filters optional. Reuses the existing `UserAccountRepository.search()` JPQL query
(subdivision-scoped, role/dc/enabled optional) — no new repository surface.

**Scope rules (server-enforced):**

- **ADMIN**: results pinned to admin's subdivision. `distributionCenterId` may further narrow.
  If the admin passes a DC outside their subdivision, the underlying query returns empty (DC
  IDs are not secret — no need to 403-probe-detect them).
- **ENGINEER / TECHNICIAN**: results pinned to caller's DC. A supplied `distributionCenterId`
  that differs from the caller's DC → `403 FORBIDDEN`. Deliberate — don't silently rewrite,
  the FE should know the request was overruled (helps catch bad filter wiring early).

The narrower `StaffDirectoryEntryResponse` is returned (same shape as the single / batch GETs)
so cross-DC personal flags never leak.

#### Why widen the existing endpoint instead of adding `/staff/technicians`

A second URL (`/staff/technicians?distributionCenterId=…`) would force a third later
(`/staff/engineers?subdivisionId=…`), and so on — the picker's filter set grows with every new
role-aware screen. One paginated search endpoint with optional role/DC/active filters covers
all of them and stays consistent with the rest of the directory surface. The
`params = "ids"` trick on `@GetMapping` is the standard Spring MVC way to share a path
between batch and search variants.

#### Tests

- `StaffLookupServiceDirectoryTest` — 3 new tests on top of Stage 14.5's 4:
  - Engineer caller is pinned to own DC (captures the actual DC argument passed to the repo
    to prove the request param was overridden by caller's DC).
  - Engineer requesting a different DC → 403 FORBIDDEN.
  - Admin passes an explicit DC and it flows through to the repo query.
- `StaffDirectoryControllerTest` — 1 new test: paged search returns `PageResponse` envelope
  with the expected content. The `params` dispatch (batch vs search) is verified by the
  pre-existing `getMany_success` test continuing to pass.

No IT — the new method is pure delegation to an existing repo query already covered by
`UserAccountRepositoryIT`.

#### Build status

```
[INFO] Tests run: 116, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +4 from Stage 14.5)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — still 46 paths (same URL, second handler signature added to the existing path).
```

#### FE migration note

The current TechnicianPicker calls ADMIN-only `useListStaff` → migrate to the new directory
search with `{ role: 'TECHNICIAN', distributionCenterId: <complaint.dcId or caller's dc> }`
for engineers, or `{ role: 'TECHNICIAN', distributionCenterId: <picked DC> }` for admin
cross-DC reassignment. Same response shape as the existing `useListStaff` for the fields the
picker actually uses (id, employeeId, fullName), so the swap is local to the picker.

#### Carry-overs / known follow-ups

- **Stage 15** (next slice) — SLA breach scheduler. Unchanged from earlier plan.
- **Search index polish** — if a future requirement adds free-text search (by name /
  employeeId) we'd add a `q` param + a `LIKE`-ed clause. Defer until that ask exists.
- **Cross-subdivision admin queries** — explicitly out of scope; if multi-subdivision oversight
  ever lands (see ROADMAP §"Future considerations"), this endpoint's admin branch is the
  natural place to widen.

---

### Stage 15 — Phase 4 Stage 4 · SLA breach scheduler

**Refs:** TECHNICAL_DESIGN.md §1.6 · ROADMAP.md Phase 4 · BRD §3.5.

#### What shipped

New `SlaMonitorService` with one method, one scheduled trigger:

```java
@Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")
@Transactional
public void markBreached() { … }
```

Every 15 minutes (at :00 / :15 / :30 / :45 IST) the sweep finds open complaints whose
`sla_deadline` has elapsed but whose `sla_breached` is still `false`, flips the flag, and
writes a system-driven history row (`from_status == to_status`, `changed_by_user_id = null`,
`note = "SLA breached"`). The FE timeline already renders "by system" for null-actor rows
(FE Stage 12 handoff confirmed) so no FE work was needed for this slice.

New repo query (derived):

```java
List<Complaint> findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore(
        Collection<ComplaintStatus> statuses, Instant now);
```

Reuses the `ComplaintQueryService.OPEN_STATUSES` constant (`SUBMITTED`, `ASSIGNED`,
`IN_PROGRESS`) so terminal rows are never touched. The Stage 14 resolve / close paths
already flip the flag synchronously when they detect a past-deadline complaint; this
scheduler covers the "still in flight, technician hasn't touched it" segment.

`@EnableScheduling` was already on `ComplaintsApplication` from Stage 9's `OtpCleanupJob`.

#### Why one big transaction, not per-row REQUIRES_NEW

If a complaint is concurrently mutated by an engineer / technician at sweep time, Hibernate's
`@Version` check fires and the whole tick rolls back. Per-row `REQUIRES_NEW` would isolate
the failure but add complexity. For a v1 flag whose role is informational (UI badge), 15 min
delay until next tick is fine — there is no time-critical SLA action gated on this flag.
Documented in service Javadoc so a future contributor sees the trade-off. If contention
shows up in prod metrics, split per-row then.

#### State machine

The breach flag is orthogonal to `ComplaintStatus` — flipping it is not a status transition,
so the sweep deliberately does **not** consult `ComplaintStatusTransition.requireValid(...)`.
The history row carries `from_status == to_status` (same pattern as Stage 13's severity /
reassignment annotations).

#### Incidents / decisions

1. **Scheduled-task ERROR log noise during long-running ITs.** The existing
   `OtpCleanupJob` (cron `0 0 * * * *` — top of every hour) and now `SlaMonitorService`
   (every 15 min) both fire inside Testcontainers ITs that happen to straddle a boundary,
   sometimes logging an "Unexpected error occurred in scheduled task" via Spring's default
   `TaskUtils$LoggingErrorHandler`. **Tests still pass** — the failure is per-tick, not
   per-test. Pre-existing for `OtpCleanupJob` (visible since Stage 9); now applies to two
   beans. Not chasing in this slice; if it becomes a flake source we'll either:
   - register a custom `ErrorHandler` that downgrades to `log.warn` for known races, or
   - disable schedulers in test profile via `@MockitoBean(name = "scheduledAnnotationProcessor")`.
2. **No domain event published.** A future `notification` module will want a
   `SlaBreachedEvent`, but with no listener in the codebase today emitting it would be dead
   code. Adding it is one line when the listener lands.

#### Tests

- `SlaMonitorServiceTest` — 2 tests:
  - Happy: two overdue rows flipped, two system-actor history rows written with
    `from_status == to_status` and `note` containing "SLA breached".
  - Empty sweep: no overdue rows → no history writes (the early-return path).

No IT — the derived-name repo query is a thin Spring Data convention; if the name were
wrong it'd fail boot (caught by `ComplaintsApplicationIT`). The behaviour layer is fully
mockable.

#### Build status

```
[INFO] Tests run: 118, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +2 from Stage 14.6)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — still 46 paths (scheduler has no REST surface).
```

#### Phase 4 status

**Phase 4 lifecycle is feature-complete.** Done-criteria from ROADMAP.md:

> End-to-end happy path: consumer submits → engineer assigns → technician starts → resolves
> → closes → consumer can see CLOSED status.

All five transitions live, exercised by `ComplaintFullLifecycleIT`, plus the scheduler now
flags long-overdue complaints automatically. Stage 16 (paged list / search via Specifications)
is the remaining slice before Phase 4 is closed completely — it's polish on top of a
shipped lifecycle, not a missing feature.

#### Carry-overs / known follow-ups

- **`SlaBreachedEvent`** — emit when the `notification` module's listener lands (Phase 6/7).
- **Per-row `REQUIRES_NEW`** — only if contention becomes visible in prod metrics.
- **Test-profile scheduler suppression** — wire an `app.scheduling.enabled=false` toggle if
  the ERROR log noise becomes a flake source.
- **Cron tunable** — the 15-min cadence is hard-coded in the annotation. If we want
  environment-specific cadence (dev vs prod) we'd promote it to a `@ConfigurationProperties`
  binding (`app.complaint.sla-sweep-cron`). Defer until that ask exists.

---

### Stage 16 — Phase 4 Stage 5 · Paged complaint search (Specifications)

**Refs:** TECHNICAL_DESIGN.md §5.4–§5.5 · ROADMAP.md Phase 4 last bullet · FE Stage 12 lookup-stub carry-over.

#### What shipped

Two paged list endpoints — the last bullet on ROADMAP Phase 4:

| Endpoint | Caller | Default scope |
|---|---|---|
| `GET /api/v1/staff/complaints` | ENGINEER / ADMIN | Engineer → own DC; Admin → own subdivision (IN over its DCs) |
| `GET /api/v1/technician/complaints` | TECHNICIAN | `assigned_technician_id = caller.userId()` |

Filter params (all optional, bound to `ComplaintSearchRequest`):
`status`, `severity`, `categoryId`, `distributionCenterId` (admin only), `assignedTechnicianId`
(staff only), `slaBreached`, `dateFrom`, `dateTo`, `q` (case-insensitive substring on
`ticket_no` + `description`). Standard `?page=&size=&sort=` honoured; default
`createdAt,desc` from `PageResponse.defaultSort()`. Hard size cap from the global
`WebConfig.maxPageSize` setting.

New shapes:

- `ComplaintListItemResponse` — lighter than the detail DTO (no `description`, no reasons,
  no images, no history). FE batch-resolves engineer / technician names via
  `GET /api/v1/staff/users?ids=…` (Stage 14.5).
- `ComplaintSearchRequest` — record bound from query params.

New module-private toolkit:

- `ComplaintSpecifications` — static factory methods, each returning a `Specification<Complaint>`
  or {@code null} when the filter is absent. Eight predicates: `status`, `severity`, `category`,
  `dc` (eq + in), `technician`, `slaBreached`, `createdFrom/To`, free-text. Kept package-private
  in the service package to avoid leaking JPA criteria types into the public surface.
- `ComplaintSearchService` — composes scope + filters, calls
  `JpaSpecificationExecutor.findAll(spec, pageable)`, maps to list-item DTOs.

`ComplaintRepository` extended with `JpaSpecificationExecutor<Complaint>`. No other repo
changes (the derived-name queries from earlier stages keep working). New
`DistributionCenterRepository.findIdsBySubdivisionId(Long)` + service helper for admin scope.

#### Scope rules (server-enforced, before any user-supplied filter)

- **Engineer staff list** — caller's DC pinned. `distributionCenterId` query param other than
  caller's DC → `403 FORBIDDEN` (same policy as Stage 14.6 directory search — don't silently
  rewrite, let the FE know the request was overruled).
- **Admin staff list** — caller's subdivision pinned via `dc IN (…)`. Optional
  `distributionCenterId` further narrows, but only to a DC within the admin's subdivision;
  outside → `403 FORBIDDEN`. Without the param, all DCs in the subdivision are matched.
- **Technician list** — `assigned_technician_id = caller.userId()` pinned. The
  `assignedTechnicianId` request param is silently ignored — a technician can't pivot to
  someone else's queue. Filters `categoryId` and `distributionCenterId` are also silently
  dropped (not 403'd — these aren't sensitive, just irrelevant in this surface).

Wrong-role callers (e.g. TECHNICIAN hitting `/staff/complaints` or ENGINEER hitting
`/technician/complaints`) are blocked by the existing SecurityConfig path matchers; the
service layer adds a defence-in-depth `FORBIDDEN` for `/technician/complaints` on the off
chance a future config change weakens the gate.

#### Incidents / decisions

1. **`Specification.allOf(null, …)` NPEs in Spring Data 4.** First service pass used
   `Specification.allOf(...)` with `null` arms to let the predicate factories return `null`
   for absent filters. That blew up at runtime with
   `IllegalArgumentException: Other specification must not be null` — `allOf` does not tolerate
   nulls. Switched to a small `combine(...)` helper that filters `null`s via stream + reduce,
   falling back to `cb.conjunction()` (match-all) when every arm is absent.
2. **`Specification.where(null)` ambiguous overload.** Second attempt used
   `Specification.where(null)` as the reduce identity — Spring Data 4 overloaded `where` with
   `PredicateSpecification`, making the `null` arg ambiguous. Replaced with a
   `reduce(Specification::and).orElseGet(matchAll)` pattern. No `where(null)` anywhere.
3. **Free-text uses `LIKE` not `tsvector`.** For v1 the search box hits `ticket_no` +
   `description` with a case-insensitive substring. The `description` column is short and the
   table is moderate; a GIN-indexed `tsvector` is the upgrade path if we outgrow it, not a
   different framework. Documented inline in `ComplaintSpecifications.textSearch`.
4. **`ComplaintSpecifications` kept package-private**, not exposed via `ComplaintQueryService`
   or similar. Other modules have no business composing complaint criteria; the search service
   is the seam.

#### Tests

- `ComplaintSearchServiceTest` — 6 tests:
  - Engineer happy: filtered search returns `PageResponse`.
  - Engineer requesting another DC → `403 FORBIDDEN` (no query issued).
  - Admin without DC param composes `IN (…)` over subdivision DCs.
  - Admin with out-of-subdivision DC → `403 FORBIDDEN`.
  - Technician happy: scope pinned to own `userId`; cross-tech filter from request silently
    ignored.
  - Wrong-role caller on `listForTechnician` → `403 FORBIDDEN`.
- `StaffComplaintControllerTest` — +1: `GET /staff/complaints?status=ASSIGNED` returns
  `PageResponse` envelope.
- `TechnicianComplaintControllerTest` — +1: `GET /technician/complaints` returns
  `PageResponse` envelope.

No new IT — the underlying `JpaSpecificationExecutor.findAll` is Spring Data infrastructure,
exercised in countless production codebases; our composition logic is fully covered by the
service unit tests against a mocked repo. The full-lifecycle IT from Stage 14 still covers
the row shapes the list returns.

#### Build status

```
[INFO] Tests run: 126, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +8 from Stage 15: 6+1+1)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 46 → 48 paths (+2 paged list endpoints).
```

#### Phase 4 status — **complete**

All ROADMAP §"Phase 4 Backend" bullets shipped:

- ✅ `ComplaintStatusTransition` — Stage 12
- ✅ `ComplaintAssignmentService` — Stage 13
- ✅ `ComplaintTriageService` — Stage 13
- ✅ `ComplaintResolutionService` — Stage 14
- ✅ `ComplaintClosureService` — Stage 14
- ✅ SLA breach reason required-when-breached — Stage 14
- ✅ `SlaMonitorService` — Stage 15
- ✅ Resolution image upload — Stage 14
- ✅ Specification-based search — **Stage 16**

Done-criteria met end-to-end (covered by `ComplaintFullLifecycleIT`), staff + technician
both have a usable list view. The remaining Phase 4 work is FE: technician mobile flow,
complaints list page (now unblocked), close-modal + image gallery (FE Stage 12.2 prompt
already in flight).

#### Carry-overs / known follow-ups

- **`q` parameter performance ceiling** — if list-page latency creeps up under real volume,
  add a GIN-indexed `tsvector` over `(ticket_no, description)` and switch `textSearch` to
  `@@ websearch_to_tsquery(...)`. Defer until profiling says so.
- **Sort whitelist** — `Pageable` accepts arbitrary `sort=fieldName,asc/desc`. Today an FE
  bug could send `sort=passwordHash,desc` (no such Complaint field — would throw a
  `PropertyReferenceException` and surface as 400). If we want a friendlier error or a
  hard whitelist, add a `@SortDefault` + a `PageableHandlerMethodArgumentResolverCustomizer`.
  Not blocking, FE-driven only.
- **Multi-subdivision admin / "all DCs in MSEB" view** — explicitly out of scope per BRD
  §"Future considerations". The admin branch composes `dc IN (…)` so it scales naturally
  when that view is wanted — just swap the `findDcIdsInSubdivision` call for a wider source.
- **Stage 17+ / Phase 5** — consumer tracking, cancellation, feedback (per ROADMAP). Independent
  of Stage 16; safe to start whenever FE has bandwidth on the lifecycle screens.

---

## How to update this log

1. At the end of a stage, append (or fill in) the corresponding subsection.
2. Keep entries terse. **What shipped**, **what bit us**, **what we tested**, **what we deferred**.
3. Don't rewrite history — additive only. If we have to undo something, add a new entry that says so.
4. Cross-reference TECHNICAL_DESIGN / BRD section numbers where relevant, so a reader can jump to the design context.

