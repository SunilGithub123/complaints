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
- **OpenAPI spec-drift CI guard** (still tracked from Stage 3 + Stage 6) — this stage proved it's needed: a previous stage's controller change shipped without re-snapshotting `docs/openapi.json`. The IT writes the file but doesn't fail on uncommitted drift. Phase 7 plan: add a `git diff --exit-code docs/openapi.json` step after the failsafe phase in CI.
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

- `openapi/OpenApiExportIT` — 1 IT. Both an assertion (`200`, body contains `"openapi"`) **and** a build-artifact producer (writes `docs/openapi.json`). Running `./mvnw verify` keeps the snapshot in sync with the live spec; CI will fail if the spec stops being valid JSON or the server stops booting. No unit tests — there is no business logic to mock; the value is in the live spec round-trip.

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
  `@ConditionalOnProperty("app.sms.provider=msg91")`)
    — logs the OTP at `INFO` so the dev flow stays click-through. MSG91 implementation is
    deferred (see follow-ups).
  - `SmsDeliveryException` — narrow checked-style runtime carrier so future MSG91 errors
    don't leak as raw `RestClientException`.
  - `OtpProperties` (`@ConfigurationProperties(prefix = "app.auth.otp")`) — `length`, `ttl`,
    `cooldownSeconds`, `maxPerMobilePerHour`, `maxAttempts`. Defaults bound in
    `application.yml`: length=6, ttl=PT5M, cooldown=30, maxPerHour=5, maxAttempts=5.
  - `OtpCleanupJob` — `@Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")` purges
    rows with `expires_at < now - 24h`. Per hard-rule #1: explicit IST zone.
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
- **Keeps** `subdivisionId` / `distributionCenterId` so the FE can render historical-but-now-disabled actors with a muted
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

### Stage 16.1 — FE Stage 12.2 micro-patches (Phase 4 polish)

Small, surgical changes prompted by FE Stage 12.2 feedback. No new endpoints, no schema,
no migrations — just contract sharpening so the FE can render a better gallery and avoid a
follow-up GET after close.

#### Scope delivered

- **`ComplaintImageResponse.imageType`** — added `ComplaintImageType` discriminator (enum
  `COMPLAINT | RESOLUTION`) between `id` and `contentType`. Mapper now propagates
  `img.getImageType()`. Unlocks grouped sections / badges on the FE gallery.
- **Signed-URL TTL: 15 min → 1 hour** in `ComplaintMapper.IMAGE_URL_TTL`. Lets the FE detail
  query keep a normal `staleTime` instead of `staleTime: 0` just to refresh thumbnails. URLs
  are HMAC-signed; TTL is a cache-friendliness knob, not a security primitive.
- **`POST /api/v1/staff/complaints/{id}/close`** now returns `ComplaintStaffDetailResponse`
  (status=`CLOSED`, bumped `version`, refreshed timestamps) instead of `Void`. Saves the FE
  one round-trip on close. Controller composes `closure.close(...)` + `read.getById(...)` —
  the second call also re-runs the scope check, which is fine (cheap, idempotent).
- **`GET /api/v1/staff/users` default sort** — pinned `@PageableDefault(sort="fullName",
  direction=ASC, size=20)` on the search handler. Picker dropdowns are alphabetical by default
  without the FE having to remember `?sort=fullName,asc`.

#### Asks explicitly closed as "no change needed"

- **`SLA_BREACH_REASON_REQUIRED` only fires when no existing reason** — re-verified
  `ComplaintClosureService:48-53`: the throw is gated on
  `breached && !reasonAlreadyOnFile && (request.reason == null || blank)`. A reason captured
  at resolve time is silently reused; the closer is never nagged. No change.
- **Deprecate `INVALID_TECHNICIAN`** — code does not exist in `ErrorCode.java`. The
  technician failure codes today are `TECHNICIAN_NOT_FOUND` (404, account missing/disabled)
  and `TECHNICIAN_NOT_IN_DC` (409, account exists but wrong DC). FE matcher can drop the
  `INVALID_TECHNICIAN` branch — it's pure FE legacy from an earlier spec draft.

#### Incidents fixed during implementation

- **None of note**. The IDE-cache import drift from Stage 16.1 recurred twice (mapper +
  controller + test imports silently reverted after replace) — caught by the next `./mvnw`
  compile error each time and re-applied. Logging here so we recognise the symptom faster:
  if `cannot find symbol` appears immediately after an apparently-successful edit, re-grep
  the imports before assuming a deeper bug.

#### Tests added

- **0 new tests** — existing `StaffComplaintControllerTest.close_success` updated in-place to
  cover the new response shape. `ComplaintImageResponse` constructor signature change is
  exercised transitively by mapper-using tests; no test was asserting on positional fields.

#### Build status

- `./mvnw verify` green.
- **126 unit + 8 IT**, OpenAPI **48 paths** (unchanged path count; response schema on
  `/staff/complaints/{id}/close` now points at `ApiResponseComplaintStaffDetailResponse`, and
  the `ComplaintImageResponse` schema gains an `imageType` enum property).

#### Carry-overs / known follow-ups

- **Image lightbox / fullscreen view** — FE-only (gallery zoom). Not BE work.
- **`If-Match` plumbing for optimistic concurrency** — still deferred until FE explicitly
  needs it (two engineers acting on the same complaint at the same time). Same story as
  before: `@Version` already raises `COMPLAINT_VERSION_CONFLICT` (409); FE just isn't
  sending the read version back yet.
- **Split images endpoint** (`GET /complaints/:id/images`) — not built. The TTL bump fully
  addresses the original complaint; a dedicated endpoint can come if/when image churn on a
  hot complaint becomes a measurable problem.


## Phase 5 — Consumer tracking, cancellation, feedback

### Stage 17 — Consumer tracking list + enriched detail + safe history

Closes the consumer-side read view of a complaint's lifecycle. Before Stage 17 the consumer
could only fetch a single just-submitted complaint by ticket number (Stage 10b). Now they can
list every complaint they've ever raised, see the SLA / resolution timestamps on the detail,
and view the chronological status timeline — all without ever seeing internal staff IDs.

#### Scope delivered

- **`GET /api/v1/consumer/complaints`** — paged tracking list. Server pins
  `consumer_master_id = caller.consumerMasterId()` via `consumerMasterIdEq(...)` spec.
  Optional `?status=…` filter. Default sort `createdAt,desc` via `@PageableDefault`.
- **`ComplaintDetailResponse` enriched** — added `severity`, `slaBreached`, `resolvedAt`,
  `closedAt`. Staff identities (engineer / technician IDs), internal reasons (rejection /
  cancellation), and audit user IDs remain on `ComplaintStaffDetailResponse` only.
- **`GET /api/v1/consumer/complaints/{ticketNo}/history`** — chronological status timeline.
  Returns `ConsumerComplaintHistoryEntryResponse` which is the staff history shape
  <b>minus</b> `changedByUserId`. Owner-checked via the existing
  `COMPLAINT_NOT_OWNED_BY_CONSUMER` path.
- **`ConsumerComplaintListItemResponse`** — narrow list-row shape (10 fields). Drops
  `assignedEngineerId`, `assignedTechnicianId`, `distributionCenterId`, `contactMobile`
  vs the staff list item — consumers don't enumerate MSEB's internal allocation.
- **`ComplaintSpecifications.consumerMasterIdEq(Long)`** — added (one-liner, mirrors
  `technicianEq`). Both the new list service and any future consumer-search filter use it.
- **`ComplaintReadService` now also depends on `ComplaintHistoryRepository`** — same module,
  no ArchUnit boundary touched.

#### Design decisions

- **Single `ComplaintReadService` handles all 3 reads** instead of splitting into
  `ComplaintReadService` / `ConsumerListService` / `ConsumerHistoryService`. All three are
  owner-checked, all three depend on the same two repositories + mapper; splitting would just
  be ceremony. The class is now ~100 lines — still well under the 300-line guidance.
- **`ConsumerComplaintHistoryEntryResponse` is a separate record, not a filtered view of
  `ComplaintHistoryEntryResponse`.** Records can't be "subset projected" at the type level,
  and a JSON-view annotation hack would couple consumer + staff serializations. Two records
  is the cheap, explicit answer.
- **`note` field reused as-is on the consumer-safe history.** Current notes are
  system-generated phrases ("Assigned", "Closed (SLA breached)", "SLA breached", "Marked
  duplicate of MH…", …) — none contain PII. If consumer-visible custom notes appear later
  we'll route them through a different field rather than retroactively redacting `note`.
- **Status filter only on the consumer list.** No `q`, `dateRange`, `categoryId`, etc. — a
  consumer's own list is already small (single-digit complaints per consumer typically); the
  status dropdown covers 95% of the "all-open vs all-closed" filtering need. Easy to widen
  later if asked.
- **Sort whitelist deliberately not enforced.** Same posture as the staff list (Stage 16
  carry-over): a bad client could send `sort=passwordHash,desc` and get a 400 from Spring
  Data's `PropertyReferenceException`. Acceptable for now; revisit if it becomes a real
  attack-surface concern.

#### Tests added (5)

- `ComplaintReadServiceTest.listOwned_happyPath` — verifies the scope spec is composed and
  every row goes through the mapper.
- `ComplaintReadServiceTest.getOwnedHistory_happyPath` + `_foreignTicket_rejected` — ownership
  enforced before history is read.
- `ConsumerComplaintControllerTest.list_happy_200` — MockMvc; asserts envelope + first-row
  fields.
- `ConsumerComplaintControllerTest.getHistory_happy_200` — MockMvc; explicitly asserts
  `$.data[0].changedByUserId` <b>does not exist</b> (the whole point of the safe shape).

Existing `ComplaintReadServiceTest` and `ConsumerComplaintControllerTest` stubs for the old
`ComplaintDetailResponse` constructor were updated for the new shape (one-line touch each).
The old happy / unhappy paths still cover the single-ticket detail flow.

#### Incidents fixed during implementation

- **None of note**. The IDE-cache import drift from Stage 16.1 recurred twice (mapper +
  controller + test imports silently reverted after replace) — caught by the next `./mvnw`
  compile error each time and re-applied. Logging here so we recognise the symptom faster:
  if `cannot find symbol` appears immediately after an apparently-successful edit, re-grep
  the imports before assuming a deeper bug.

#### Build status

- `./mvnw verify` green.
- **131 unit + 8 IT**, OpenAPI **49 paths** (was 48 — added `GET /consumer/complaints/{ticketNo}/history`;
  `GET /consumer/complaints` shares the existing path with the submit `POST`).

#### Carry-overs / known follow-ups

- **Stage 18 (consumer cancellation)** — next slice. `POST /api/v1/consumer/complaints/{ticketNo}/cancel`
  with `{ reason? }`, only valid while `status == SUBMITTED`. Reuses the state-machine allow-table
  added in Stage 12.
- **Stage 19 (feedback)** — after Stage 18. `POST /api/v1/consumer/complaints/{ticketNo}/feedback`
  with `{ rating: 1..5, comments? }`, only valid while `status == CLOSED`, once per complaint.
  `Feedback` entity already exists from Stage 10b's schema; `FEEDBACK_*` error codes already in
  the enum.
- **Phase 6 (notifications + domain events)** — push notification on status change is the
  obvious lever once a consumer is on the tracking screen. Out of scope for Phase 5.

---

### Stage 18 — Consumer cancellation

Adds the one mutation the consumer is allowed to make on a complaint after submit: withdrawing
it while it's still `SUBMITTED`. Reuses the state-machine allow-table added in Stage 12; no
schema change (`cancellation_reason` column has existed since V1.0).

#### Scope delivered

- **`POST /api/v1/consumer/complaints/{ticketNo}/cancel`** — body `{ reason?: string }`
  (optional, max 500 chars). Owner-checked. State-checked (must be `SUBMITTED`). Persists
  `cancellation_reason` if present, writes a history row, returns `ApiResponse<Void>`.
- **New service `ComplaintCancellationService`** — single-purpose, mirrors the Phase 4
  service style (assignment / triage / resolution / closure each have their own service).
- **New DTO `CancelComplaintRequest`** with `@Size(max = 500)` on the optional reason.

#### Design decisions

- **Owner check before state check.** If a non-owner sends `POST /cancel` against a foreign
  ticket they get `403 COMPLAINT_NOT_OWNED_BY_CONSUMER`, not `409 NOT_IN_SUBMITTED_STATE`.
  Same privacy posture as the read path — we don't want callers to enumerate ticket states
  by probing.
- **Narrow `COMPLAINT_NOT_IN_SUBMITTED_STATE` rather than generic `INVALID_STATE_TRANSITION`.**
  The consumer UX wants to tell the user "this can only be cancelled before MSEB starts work
  on it", not generic 409 noise. We still call `ComplaintStatusTransition.requireValid(...)`
  afterwards as a belt-and-braces check, but the user-facing code is the narrow one.
- **History `changed_by_user_id = null`, consumer external ID embedded in `note`.** Consumers
  have no `user_account` row, so the FK slot stays null — exactly the same pattern the SLA
  scheduler uses for system actors (Stage 15). Audit reconstruction reads e.g.
  `"Cancelled by consumer MH00010001"` from the `note` column. Staff history endpoint will
  surface this verbatim; consumer history endpoint shows the same `note` (no PII — only the
  consumer's own external id, which they already know).
- **`reason` normalised to `null` if blank.** Empty / whitespace-only reasons land as
  `NULL` in `cancellation_reason` rather than empty strings — keeps the staff detail screen's
  "show reason if present" check simple.
- **No `@CacheEvict` / no domain event** — the complaint list / detail queries aren't cached
  in v1, and the notification module isn't wired yet (Phase 6). When events land, this is
  the natural first publisher (`ComplaintCancelledEvent`).

#### Tests added (5)

- `ComplaintCancellationServiceTest`:
  - `cancel_happyPath` — verifies state flip, reason persisted, history row with null actor
    and consumer external id in the note.
  - `cancel_assigned_rejected` — non-`SUBMITTED` raises `COMPLAINT_NOT_IN_SUBMITTED_STATE`.
  - `cancel_foreignTicket_rejected` — foreign ownership raises `COMPLAINT_NOT_OWNED_BY_CONSUMER`
    even when state would otherwise be valid (state-leak guard).
- `ConsumerComplaintControllerTest`:
  - `cancel_happy_200` — MockMvc: delegates to service, returns success envelope.
  - `cancel_wrongState_409` — service throws → controller surfaces as 409 with the right code.

#### Incidents fixed during implementation

- **None of note.** The IDE-cache import drift recurred again on three separate edits
  (controller import + field, test imports + `@MockitoBean` field — all silently reverted between
  successive `replace_string_in_file` calls). Caught by `./mvnw` each time and re-applied;
  total cost ~3 minutes. The symptom is consistent enough now that it's quick to recognise
  ("cannot find symbol" immediately after a clean replace = re-grep imports before retrying).
  Considering a one-off "verify file contents after edit via grep" habit going forward.

#### Build status

- `./mvnw verify` green.
- **136 unit + 8 IT**, OpenAPI **50 paths** (was 49; +1 cancel endpoint).

#### Carry-overs / known follow-ups

- **Stage 19 (feedback)** — next slice. `POST /api/v1/consumer/complaints/{ticketNo}/feedback`,
  `{ rating: 1..5, comments? }`, `CLOSED`-only, idempotent (one row per complaint).
  `Feedback` entity exists from Stage 10b schema; `FEEDBACK_*` error codes already in enum.

---

### Stage 19 — Consumer feedback

Closes Phase 5. The consumer can now rate a `CLOSED` complaint with a 1–5 star rating plus an
optional free-text comment, once per complaint. `Feedback` entity + `UNIQUE(complaint_id)` index
existed since the Stage 10b schema; this slice adds the write path + validation + tests.

#### Scope delivered

- **`POST /api/v1/consumer/complaints/{ticketNo}/feedback`** — body
  `{ rating: 1..5 (required), comment?: string (≤1000) }`. Returns `201 Created` with
  `ApiResponse<FeedbackResponse>` carrying `{ id, rating, comment, submittedAt }` so the FE
  can render the "thanks for your feedback" screen without a follow-up GET.
- **New service `ComplaintFeedbackService`** — single `submit(...)` method, mirrors the
  one-class-per-business-capability pattern used by `ComplaintCancellationService` /
  assignment / triage / resolution / closure.
- **New DTOs**: `SubmitFeedbackRequest` (with `@NotNull @Min(1) @Max(5)` on rating,
  `@Size(max=1000)` on comment), `FeedbackResponse`.
- **`FeedbackRepository.existsByComplaintId(Long)`** — friendly idempotency check; the
  `UNIQUE(complaint_id)` constraint on `feedback` is the real safety net.
- **`ComplaintMapper.toFeedbackResponse(Feedback)`** — entity → response with IST timestamp.

#### Design decisions

- **Check order: ticket exists → ownership → state → duplicate.** Ownership before state
  / dup so a non-owner gets a uniform `403 COMPLAINT_NOT_OWNED_BY_CONSUMER` regardless of
  the underlying complaint's state — same privacy posture as Stage 17 reads + Stage 18
  cancellation. Probing for "is there feedback on ticket X" is not a thing we want to enable.
- **`CLOSED`-only.** `CANCELLED` / `REJECTED` / `DUPLICATE` complaints have no resolution
  to rate; `SUBMITTED` / `ASSIGNED` / `IN_PROGRESS` / `RESOLVED` aren't done yet. Net error
  code is the existing `FEEDBACK_NOT_ALLOWED_YET` (added in Stage 10b's schema-side enum prep).
- **Comment normalised to `null` when blank.** Same pattern as `cancellation_reason` —
  empty / whitespace-only land as SQL `NULL` rather than empty string, simplifying the staff
  read screen.
- **`Feedback` entity not enriched onto `ComplaintDetailResponse` yet.** The FE may want a
  "has feedback been left?" indicator on the consumer detail screen; the cheap path is a new
  field on the existing detail response. <b>Deliberately not built this slice</b> — second-time
  rule. If FE asks, it's a 10-line follow-up (one mapper arg + one `findByComplaintId` call +
  one response field). For now the FE can hold the freshly-submitted `FeedbackResponse` in
  React-Query cache.
- **No GET feedback endpoint.** Same rationale as above — premature until there's a real
  caller. The detail enrichment will fill that gap when the time comes.

#### Tests added (6)

- `ComplaintFeedbackServiceTest`:
  - `submit_happyPath` — happy path, `ArgumentCaptor` asserts comment was blank-normalised to `null`.
  - `submit_notClosed_rejected` — `IN_PROGRESS` complaint → `FEEDBACK_NOT_ALLOWED_YET`.
  - `submit_duplicate_rejected` — `existsByComplaintId` returns true → `FEEDBACK_ALREADY_SUBMITTED`.
  - `submit_foreignTicket_rejected` — ownership enforced before state / dup checks.
- `ConsumerComplaintControllerTest`:
  - `feedback_happy_201` — MockMvc: 201 + envelope + `$.data.id`/`$.data.rating`.
  - `feedback_invalidRating_400` — `rating: 6` rejected by bean-validation as
    `VALIDATION_FAILED`.

#### Incidents fixed during implementation

- **None of note.** The IDE-cache import drift recurred again (controller field + mapper
  entity import + test file imports + `@MockitoBean` field — all silently reverted between
  successive `replace_string_in_file` calls). Recognised faster this time: every
  controller / test edit was followed by an immediate `grep` to confirm persistence before
  running tests, which cut the retry loop down. Worth noting in the implementation log as a
  recurring symptom — the cause appears to be IntelliJ's auto-reformatter racing with the
  edit tool when both touch the same file in quick succession.

#### Build status

- `./mvnw verify` green.
- **142 unit + 8 IT**, OpenAPI **51 paths** (was 50; +1 `POST /feedback`).

#### Phase 5 — closing summary

| Stage | Surface | Tests |
|------:|--------|-------|
| 17    | Consumer tracking list + enriched detail + safe history (2 new GETs, detail enriched, 2 new DTOs, 1 new spec) | +5 |
| 18    | Consumer cancellation (1 new POST, 1 service, 1 DTO) | +5 |
| 19    | Consumer feedback (1 new POST, 1 service, 2 DTOs, 1 repo helper, 1 mapper method) | +6 |

Phase 5 BE done-criteria: the consumer can submit, track, history-inspect, cancel-while-eligible,
and rate-after-close every complaint they raise — with zero exposure of internal MSEB staff
identities, internal reason fields, or other consumers' tickets. All gated by the 5-minute
consumer-verify token; all owner-checked at the service layer with a uniform 403 on foreign
access. Phase 6 (notifications + domain events) is now the natural next phase.

#### Carry-overs / known follow-ups

- **Detail enrichment with `feedback`** — defer until FE asks. Cheap when needed.
- **Admin / staff feedback read surface** — staff may eventually want "average rating per
  technician / DC". Not in v1 BRD; would land in Phase 7 (analytics) anyway.
- **Notification on close** ("Your complaint is closed — rate the resolution") — Phase 6.

---

## Phase 6 — Notifications & domain events

### Stage 20 — Domain events foundation

**What shipped**

- New package `com.example.complaints.complaint.event` with a sealed marker interface
  `ComplaintEvent` and 9 record events (one per business moment): `ComplaintSubmittedEvent`,
  `ComplaintAssignedEvent`, `ComplaintReassignedEvent`, `ComplaintResolvedEvent`,
  `ComplaintClosedEvent`, `ComplaintCancelledEvent`, `ComplaintRejectedEvent`,
  `SlaBreachedEvent`, `FeedbackSubmittedEvent`. All carry primitive IDs only (no JPA
  entities) so a Stage-21 `AFTER_COMMIT` listener can never trip lazy-loading.
- Wired `ApplicationEventPublisher` into 8 services (`ComplaintCreationService`,
  `ComplaintAssignmentService` × 2 paths, `ComplaintTriageService.reject`,
  `ComplaintResolutionService.resolve`, `ComplaintClosureService.close`,
  `ComplaintCancellationService.cancel`, `ComplaintFeedbackService.submit`,
  `SlaMonitorService.markBreached`). Publish call sits after the `history.save(...)` row so
  the listener sees a complete audit trail when it fires.
- `ComplaintEventLogger` (debug-only listener) with two methods: an
  `@TransactionalEventListener(phase = AFTER_COMMIT)` at INFO and a synchronous
  `@EventListener` at DEBUG. Proves wiring without any external side effects. Will be
  replaced / supplemented by real notification listeners in Stage 21.

**Why a sealed interface over a marker annotation**

Sealing the hierarchy lets Stage-21 dispatch code use exhaustive `switch` (Java 21
pattern-matching) over `ComplaintEvent` — the compiler will flag any new event that
forgets a listener branch. An annotation-based marker would have given us no such check.

**Schema / migrations**

None. Pure in-process eventing; nothing persisted.

**Tests added**

- Each of the 8 existing happy-path service tests gained one
  `verify(events).publishEvent(any(XxxEvent.class));` assertion. High-signal per the test
  policy ("would I miss this in prod?" — yes, a silently-missed publish would mean no
  notification ever fires in Stage 21).
- No new dedicated event listener tests yet — `ComplaintEventLogger` is intentionally
  a debug aid and will be torn out in Stage 21.

**Incidents fixed during implementation**

- **`SlaMonitorService.java` corrupted by stray `\u0001` (SOH) bytes + lost `@Scheduled`
  import** between successive edits. The IDE-cache import drift recurred and this time
  the auto-reformatter also injected a non-printable control char into the import block,
  which the Java compiler reported as `illegal character: '\u0001'`. Recovered by writing
  a Python cleanup script that stripped SOH chars from every `.java` under
  `src/main/java/com/example/complaints/complaint/` and re-inserted the missing import via
  a deterministic anchor. Same pattern hit `ComplaintResolutionService` and
  `ComplaintClosureService` (lost their `ApplicationEventPublisher` import).
- **Test-side patching of 8 services in one sweep** — initially attempted via repeated
  `replace_string_in_file` calls; rapidly diverged because some test classes used
  multi-line constructor calls and others single-line, with different mock field
  conventions. Switched to a Python script (`/tmp/patch_tests.py`) that uses anchored
  regexes — landed all 8 in one shot.
- **`verify(events).publishEvent(...)` placed against the wrong happy-path method** for
  `ComplaintTriageServiceTest` (script picked `updateSeverity_happyPath` instead of
  `reject_happyPath`) and `ComplaintResolutionServiceTest` (`start_happyPath` instead of
  `resolve_onTime_happyPath`). Surface failure was "Wanted but not invoked: zero
  interactions with this mock" — clear signal, fixed by moving the assertion to the
  correct method. `SlaMonitorService.markBreached` publishes per-overdue-complaint, so
  the assertion uses `times(2)` to match the 2 overdue rows the test sets up.

**Build status**

- `./mvnw verify` green.
- **142 unit + 8 IT**, OpenAPI **51 paths** (unchanged — no new HTTP surface; events are
  internal). Test count holds at 142 because we replaced the wrong placements rather than
  adding extras.

**Carry-overs / known follow-ups**

- **Stage 21**: real notification module — per-event listeners that fan out to FCM (push)
  + console SMS (mock) per role. Will introduce `notification/` top-level package.
- **Stage 22**: persistence of in-app notifications + read-state per user (table +
  unread-count endpoint).
- **No event-bus IT yet** — once Stage 21 lands a real listener with observable side
  effects, we'll add one `@SpringBootTest` that publishes a real event and asserts the
  listener observed it after `AFTER_COMMIT`. Doing it now (against a debug logger) would
  test nothing.

---

### Stage 20.1 — FE Stage 13 carry-over (feedback discoverability)

**What shipped**

- `ComplaintDetailResponse.feedbackSubmitted: boolean` — lets the consumer detail screen
  hide the Rate button on first paint (and after a tab restore) without a probe POST that
  catches the 409. Marked `@Schema(requiredMode = REQUIRED)` so orval emits it as
  non-optional.
- New `GET /api/v1/consumer/complaints/{ticketNo}/feedback` returning
  `ApiResponse<FeedbackResponse>`. Returns 200 with `data: null` when no feedback row
  exists yet — that intentionally is **not** an error code, since "no feedback yet" is the
  normal pre-submit state and the FE renders the panel only when data is non-null. Owner-
  checked; foreign ticket → 403 `COMPLAINT_NOT_OWNED_BY_CONSUMER`.
- `FeedbackRepository.findByComplaintId(Long)` for the read-back.
- `ComplaintReadService` now injects `FeedbackRepository` and calls `existsByComplaintId`
  on the detail path; mapper signature gained the boolean. Detail path stays one query
  per existence check — no join needed.

**Why GET-returns-null instead of 404**

A 404 for "feedback not submitted yet" would force the FE into try/catch flow control and
collide semantically with "complaint not found" (same status). Per the over-engineering
rules: add the abstraction (an error code) the *second* time you need it. Today the
existence flag (`feedbackSubmitted`) tells the FE whether to call GET at all; the GET only
runs in the affirmative case, and returning `null` defensively keeps the contract trivial.

**Item not actioned**

- **`imageType` schema optionality** — re-verified: `docs/openapi.json` already lists
  `imageType` under `required` (shipped in Stage 16.1's `@Schema(requiredMode = REQUIRED)`
  annotation). FE was reading a stale spec; no BE change needed.

**Tests added (5 new, total 147 unit + 8 IT)**

- `ComplaintFeedbackServiceTest`:
  - `getOwned_existing_returnsMapped`
  - `getOwned_missing_returnsNull`
  - `getOwned_foreignTicket_rejected` (403 leak guard, mirrors submit path)
- `ConsumerComplaintControllerTest`:
  - `getFeedback_existing_200`
  - `getFeedback_missing_200_null` (asserts `$.data` does not exist)
- `ComplaintReadServiceTest.getOwnedByTicketNo_ownedTicket_returnsDetail` updated to
  stub the new `feedbackRepo.existsByComplaintId` call + the new mapper signature.

**Build**

- `./mvnw verify` green. **147 unit + 8 IT.** OpenAPI **51 paths** (unchanged — no new
  endpoint). `ConsumerComplaintListItemResponse` schema lists `feedbackSubmitted` under
  `required`.

---

### Stage 20.2 — FE Stage 13 follow-up (feedback hint on tracking list)

**What shipped**

- `ConsumerComplaintListItemResponse.feedbackSubmitted: boolean` — lets the tracking list
  render a "Rated" / "Awaiting feedback" hint on each CLOSED row without a per-row probe.
  Marked `@Schema(requiredMode = REQUIRED)` so orval emits a non-optional field.
- `FeedbackRepository.findComplaintIdsWithFeedback(Collection<Long> ids)` — one batched
  IN-list query per page; the service collects the page's complaint IDs, runs the lookup
  once, and the mapper threads the boolean through. Page size is capped at 100 so the IN
  list stays bounded. No N+1.
- New mapper overload `toConsumerListItem(Complaint, boolean)`; the zero-arg version is
  retained (defaults to `false`) so unrelated callers don't have to change.

**Item not actioned**

- **POST `/feedback` returning the persisted row** — re-verified the response is already
  `ApiResponse<FeedbackResponse>` carrying the saved row (Stage 19 shipped it this way).
  FE was discarding the body; no BE change needed. The FE can `setQueryData(...)` from
  the POST response directly and skip the follow-up `useGetFeedback` refetch.

**Tests added (1 new, total 148 unit + 8 IT)**

- `ComplaintReadServiceTest.listOwned_marksFeedbackSubmitted` — two-row page with one
  feedback row present; verifies the batch lookup result is mapped onto the correct row
  and the other row stays `false`. The existing `listOwned_happyPath` was updated to
  stub `findComplaintIdsWithFeedback` returning empty and to call the new 2-arg mapper.
- Controller `list_happy_200` updated for the new constructor arity; no new controller
  test — the contract change is the schema field, which is already covered by the
  service-level batch test plus the OpenAPI assertion.

**Build**

- `./mvnw verify` green. **148 unit + 8 IT.** OpenAPI **51 paths** (unchanged — no new
  endpoint). `ConsumerComplaintListItemResponse` schema lists `feedbackSubmitted` under
  `required`.

---

### Stage 20.3 — End-to-end smoke harness + `JwtAuthFilter` consumer-path fix — ✅ 2026-06-24

> While wiring up `scripts/smoke.sh` as the "did I just break the wire contract?"
> 20-second sanity check, the first run against a freshly-restarted dev backend
> exposed a real production bug in the security filter chain. Caught the bug *before*
> any FE consumer-flow demo, justifying the smoke harness on day one.

#### Scope delivered

**Production fix** (`auth.security.JwtAuthFilter`):

- Added `shouldNotFilter(HttpServletRequest)` returning `true` for any URI starting with
  `/api/v1/consumer/`. Without it the filter was active on every request that carried a
  `Bearer` header, including consumer routes — it would parse the consumer-verification
  JWT, see `typ=consumer` (not `access`), and short-circuit with
  `401 UNAUTHORIZED "Token is not an access token"` **before** `ConsumerVerificationFilter`
  ever ran. The dev `OpenApiExportIT` and the consumer-side `ConsumerComplaintControllerTest`
  did not catch this because both run with `addFilters = false`; the production filter
  chain was only exercised by the missing end-to-end smoke.
- Matches the existing `shouldNotFilter` pattern already on
  `ConsumerVerificationFilter` (which itself skips non-consumer paths). The two filters
  now have symmetric, disjoint responsibility.

**Smoke harness** (`scripts/smoke.sh` — already in the repo, hardened this stage):

20-step end-to-end happy-path drive: admin first-login → masterdata lookup → create
engineer + technician → first-login + change-password for both → consumer OTP
send → verify → submit (multipart) → engineer assign → technician start + resolve →
engineer close → consumer detail (`feedbackSubmitted=false`) → consumer history
(consumer-safe shape) → submit feedback → read-back → detail flips
`feedbackSubmitted=true` → tracking list shows the hint. Auto-reads the dev OTP from
`$APP_LOG_FILE` (the `[DEV-SMS]` log line) so the run is non-interactive.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | `POST /api/v1/consumer/complaints` → `401 UNAUTHORIZED "Token is not an access token"` even with a valid consumer verification JWT. | `JwtAuthFilter` had no `shouldNotFilter` override → ran on consumer routes and rejected the non-`access` token before `ConsumerVerificationFilter` could see it. | Production fix above. The `OpenAPI security scheme` work in Stage 3 declared the two distinct schemes (`bearerAuth`, `consumerVerifyToken`) but the runtime filter chain never enforced the routing. |
| 2 | `POST /admin/staff/{id}/reset-password` returned 200 but the engineer / technician could not log in with the script's hard-coded `ENG_PASSWORD_RESET` value. | The reset-password endpoint **generates** a 16-char random temp password server-side and returns it in `data.temporaryPassword` — it takes **no** request body. The smoke script was sending `{"newPassword":"…"}` (silently ignored) and then trying to log in with that ignored value. | Smoke script now captures `data.temporaryPassword` from the reset-password response and threads it into the subsequent `login_and_clear_reset` call. Both temp passwords are surfaced once in script-local vars and never logged. |
| 3 | `GET /api/v1/staff/masterdata/distribution-centers` returned `data.content[...]` (`PageResponse`) but the script's jq selector used `.data[…]`, silently producing an empty `DC_ID`. | Script was treating the response as a bare array. | Switched the DC + category selectors to `.data.content[]` and bumped page size to 200. |
| 4 | Create-staff returned `400 VALIDATION_FAILED` with `employeeId must be uppercase alphanumerics or hyphens`. | Default `SMOKE_ENG_007` / `SMOKE_TECH_007` used underscores. | Defaults changed to `SMOKE-ENG-007` / `SMOKE-TECH-007`. |
| 5 | Bootstrap admin login failed with `BAD_CREDENTIALS` even though `BOOTSTRAP_ADMIN_PASSWORD=ChangeMe!123` was set. | Admin already existed from a previous dev session at a changed password; `AuthBootstrapRunner` is a no-op once any active admin exists. | Documented the one-liner reset (`UPDATE user_account SET password_hash=<bcrypt-of-ChangeMe!123>, password_reset_required=true WHERE employee_id='ADMIN001'`) in the script header. Not encoded in the script itself — destructive ops against the DB should stay an explicit operator action, not a side effect of running a smoke. |

#### Tests added

- **0 new automated tests.** The production fix is one line in a filter; the canonical
  way to assert "the right filter handles the right URL" is end-to-end against the real
  chain, which is exactly what `scripts/smoke.sh` now does. Adding a synthetic
  `@SpringBootTest` for filter routing alone would re-test Spring, not our code.
- **Carry-overs flagged for Phase 7:** the full smoke (or a stripped-down equivalent)
  should run as a CI gate against a Testcontainers-backed boot, alongside the existing
  spec-drift guard. Until then `./scripts/smoke.sh` is the human-driven version.

#### Build status

- `./mvnw verify` would still pass — the filter change is additive (the new
  `shouldNotFilter` only relaxes behaviour on routes we never want this filter to act on).
- Smoke run: **20/20 steps green**, ticket `MH20260600000001`, feedback round-tripped.
- Test counts unchanged: **148 unit + 8 IT**, OpenAPI **51 paths**.

#### Carry-overs / known follow-ups

- **CI smoke** — wrap `smoke.sh` in a job that boots the BE via Testcontainers (reuse the
  pattern from `ComplaintsApplicationIT`), runs the script against the random port, and
  fails CI on any red step. Pairs naturally with the Phase 7 spec-drift guard.
- **Symmetric `shouldNotFilter` for `PasswordResetRequiredFilter`** — same review pass
  flagged that the password-reset filter also runs on consumer routes (it's a no-op there
  because `SecurityContextHolder` will hold a `VerifiedConsumer`, not an
  `AuthenticatedStaff`, but the wasted hop is sloppy). Cheap follow-up; do it the next
  time we touch the security wiring.
- **Smoke against staff-list filters by `employeeId`** — `reset_password()` in the script
  fetches the whole staff page and filters client-side because `?employeeId=` is not an
  API filter param. If we ever add such a filter to `StaffAdminService.search(...)`, the
  script's helper collapses to a single query.

### Stage 20.4 — Stage 21 contract handed to FE for sign-off + closure-authz gap recorded — ✅ 2026-06-25

> No code shipped this stage. Process / coordination entry, plus one spec-vs-code gap
> uncovered during a sanity audit of the smoke run, recorded so it doesn't get lost
> while the BE waits on FE.

#### Scope delivered

**Stage 21 contract handoff** (push notifications / device tokens):

- `docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md` (BE-authored draft, 11 sections, ~350 lines)
  copied as-is into the sibling FE repo at the same path under `complaints-frontend/docs/`.
- FE prompted to (a) read it end-to-end, (b) answer every open question in §9
  ("Open questions for the FE") with a one-line justification per answer, and
  (c) flag any disagreement with §2–§7 before BE writes any code.
- **BE is parked on Stage 21.** No schema, no endpoints, no `PushService` — implementation
  starts only after the FE answer arrives and the doc is flipped from
  `Status: draft for FE sign-off` to `Status: FROZEN — implementing in Stage 21.1`.
- This matches the parallel-track discipline we've used through Stages 7, 11, 16.1, 20.1,
  20.2 — the contract freezes **before** code on either side.

**Closure-authorisation gap recorded** (no code change yet, documentation only):

- Sanity audit of the Stage 20.3 smoke run flagged that engineer closed the complaint,
  which works in the current code but **does not match the BRD**. BRD §4.2 (Technician
  role) and §4.8 (Resolution & Closure) say the technician is the *normal* closer and
  engineer / admin is the *on-behalf* fallback. `TECHNICAL_DESIGN.md` §6 line 366 already
  documents `POST /technician/complaints/{id}/close`, with `slaBreachReason` mandatory
  when `sla_breached = true`. The code, however, only exposes the staff close path
  (`/api/v1/staff/complaints/{id}/close`, restricted to ENGINEER / ADMIN by
  `SecurityConfig` line 60), and `ComplaintScopeGuard` actively throws `FORBIDDEN` for
  `TECHNICIAN`. Today's state: technicians can resolve, but **cannot close**.
- **Decision (BRD-faithful, no doc edits required):** ship a Stage 21.x sub-stage to add
  the technician-close path. Policy stays exactly as BRD §4.8 already states — technician
  closes own assigned complaint as the normal path; engineer / admin keep the on-behalf
  fallback; `slaBreachReason` mandatory from whoever closes when `sla_breached = true`.
- BRD and `TECHNICAL_DESIGN.md` already say this verbatim — confirmed in this audit, no
  amendments needed.

#### Incidents fixed during implementation

_None — process / coordination entry._

#### Tests added

_None — no production code touched this stage._

#### Build status

- No build / test deltas. Counts unchanged: **148 unit + 8 IT**, OpenAPI **51 paths**.
- Smoke run state unchanged from Stage 20.3 (20/20 green).

#### Carry-overs / known follow-ups

- **Stage 21 — blocked on FE.** Waiting on FE's §9 answers. Resume Stage 21.1 (schema
  `V1.5__device_token.sql` + four REST endpoints + `DeviceTokenService`) the moment the
  contract is frozen.
- **Stage 21.x — technician-close endpoint.** Add `POST /api/v1/technician/complaints/{id}/close`
  with body `{ slaBreachReason? }`. Scope check is **assigned-technician-only**
  (`complaint.assignedTechnicianId == caller.userId()`) — *not* the DC scope used by the
  engineer / admin path, so a new branch in `ComplaintScopeGuard` (or a sibling
  `requireAssignedTechnician(...)` helper, depending on which reads cleaner). Reuse the
  existing SLA-breach-reason gate in `ComplaintClosureService` so the same rule applies
  to all three actors. Tests: 1 happy (technician closes own RESOLVED complaint) + 1
  unhappy (technician tries to close someone else's → `COMPLAINT_OUT_OF_SCOPE`) + 1
  unhappy (breached complaint, missing `slaBreachReason` → `SLA_BREACH_REASON_REQUIRED`).
- **Optional tightening — `TECHNICAL_DESIGN.md` line 355.** The staff close-endpoint
  description currently says "Allowed from `IN_PROGRESS` or `RESOLVED`; also from
  `SUBMITTED`/`ASSIGNED` only when `sla_breached = true`…" — but the state machine
  (`ComplaintStatusTransition`) only allows `RESOLVED → CLOSED`. Either tighten the doc
  to match the code (recommended), or widen the state machine in a future stage if the
  closed-from-earlier-state path is ever genuinely needed for SLA-breach triage. Do the
  doc tightening at the same time as the technician-close stage above.
- **Filler work while BE is parked on Stage 21** — pick from Phase 7 ops chores that
  don't touch the wire contract: git SHA in `/actuator/info`, cache hit/miss metrics on
  `/actuator/prometheus`, JSON log layout in the prod profile. Each is independent and
  can land in any order.


### Stage 20.5 — Technician close path (BRD §4.8 parity) — ✅ 2026-06-25

> Closes the closure-authorisation gap recorded in Stage 20.4. The BRD has always said the
> technician is the *normal* closer; the code shipped in Stage 14 only exposed the
> engineer/admin on-behalf path. This stage adds the technician path so the code matches
> the doc.

#### Scope delivered

**Production code**

- **`ComplaintClosureService` refactor** — extracted private `doClose(c, caller, req)`
  carrying the state transition + SLA-breach-reason gate + history append + event publish.
  Two public entry-points now share it:
  - `close(AuthenticatedStaff, Long, CloseComplaintRequest)` — engineer / admin on-behalf
    (unchanged behaviour, still uses `ComplaintScopeGuard.requireInScope(...)`).
  - **`closeByTechnician(AuthenticatedStaff, Long, CloseComplaintRequest)`** — new entry-point.
    Scope check is `complaint.assignedTechnicianId == caller.userId()`; mismatch throws the
    existing `COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN` (403). Same SLA-breach-reason rule applies
    to both paths — required when `sla_breached = true` and no reason was captured at resolve.
- **`TechnicianComplaintController`** gained `POST /api/v1/technician/complaints/{id}/close`
  with body `CloseComplaintRequest`. Role gate is the existing `/api/v1/technician/**` →
  `hasRole("TECHNICIAN")` matcher in `SecurityConfig`; per-complaint scope is enforced by the
  service.
- **State machine unchanged** — `ComplaintStatusTransition` already allowed `RESOLVED → CLOSED`
  from Stage 12; both close paths route through `requireValid(...)` so earlier-state closes
  remain refused.
- **No new error code** — `COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN` (403) and
  `SLA_BREACH_REASON_REQUIRED` (400) both already existed from Stage 14.
- **No Flyway migration** — pure code change, schema unchanged.

**Docs**

- `TECHNICAL_DESIGN.md` §5.4 (staff close row) tightened: the previous wording allowed close
  from `IN_PROGRESS`/`RESOLVED` and even from `SUBMITTED`/`ASSIGNED` when breached, which
  contradicts the state machine in code (`ComplaintStatusTransition` permits only
  `RESOLVED → CLOSED`). Rewritten to match the code and to explicitly call out the
  engineer/admin path as the on-behalf path per BRD §4.8.
- **BRD — no changes needed.** §4.2 (Technician row) and §4.8 (Resolution & Closure) already
  state the agreed policy verbatim, confirmed in the Stage 20.4 audit. Re-verified.
- **`STAGE_21_DEVICE_TOKEN_CONTRACT.md` — no changes**, unrelated to this stage.

#### Why a refactor instead of a parallel service

Two close paths, one set of post-conditions (status / closed_at / breach flag / history row /
domain event). Duplicating that into a `ComplaintTechnicianClosureService` would have meant
two near-identical methods to keep in sync. The extracted private `doClose(...)` is 22 lines;
the two public methods are 8 lines each and differ only in their scope check. Matches the
"add the abstraction the second time you need it" rule — this is exactly that second time.

#### Why the technician close returns `Void` (not `ComplaintStaffDetailResponse`)

The staff close (Stage 16.1) returns the post-close detail so the FE can update its cache in
one round-trip. The technician version returns `Void` because:

- `ComplaintStaffReadService.getById(...)` (the only single-complaint read for staff) gates on
  `ComplaintScopeGuard`, which throws `FORBIDDEN` for `TECHNICIAN`. Reusing it would either
  require a new "read-mine" path or a guard relaxation — both are scope creep for this fix.
- The technician already has `GET /api/v1/technician/complaints` for list refresh after close.
  A single-detail read for technicians can land as a small follow-up if the FE asks (flagged
  below).

#### Incidents fixed during implementation

_None of note._ The IDE inspector flagged "Could not autowire `ComplaintRepository`" and
"never used `closeByTechnician`" on the refactored service — both are IntelliJ static-analysis
limitations (Spring Data proxies + the controller wiring resolved at runtime) and not real
compile errors. Tests + Maven build green first try.

#### Tests added (4 new — total 152 unit + 8 IT)

- `ComplaintClosureServiceTest`:
  - **`closeByTechnician_happyPath`** — assigned technician closes own `RESOLVED` complaint
    on-time → `CLOSED`, `closed_at` set, `sla_breached=false`, history saved,
    `ComplaintClosedEvent` published.
  - **`closeByTechnician_foreignTechnician_rejected`** — different `assignedTechnicianId` →
    `COMPLAINT_NOT_ASSIGNED_TO_TECHNICIAN`; state unchanged, no history, no event (defence-in-depth
    against a future bug short-circuiting the guard).
  - **`closeByTechnician_breachedNoReason_rejected`** — breached complaint, blank
    `slaBreachReason` in body, none on file → `SLA_BREACH_REASON_REQUIRED`; no history written.
- `TechnicianComplaintControllerTest`:
  - **`close_success`** — `POST /api/v1/technician/complaints/7/close` with body delegates to
    `closure.closeByTechnician(...)`, returns 200 envelope.

Existing closure tests for engineer / admin still pass (the shared `doClose(...)` private
preserves all prior behaviour).

#### Build status

- `./mvnw verify` green.
- **152 unit + 8 IT** (was 148 + 8).
- OpenAPI: **52 paths** (was 51) — new `POST /api/v1/technician/complaints/{id}/close`.
- Smoke harness unchanged — `scripts/smoke.sh` still routes close via the engineer (which still
  works); a follow-up could add a technician-close variant smoke if useful.

#### Carry-overs / known follow-ups

- **`TECHNICAL_DESIGN.md` lines 365–366 doc drift** (spotted while tightening line 355 above):
  the technician resolution-image endpoint is documented as
  `POST /technician/complaints/{id}/resolution-images`, but the code uses `/images` (Stage 14).
  And a `DELETE /resolution-images/{imageId}` is documented but never shipped. Cosmetic
  drift only — neither affects callers because the FE already uses the OpenAPI snapshot, not
  this table. Fix next time we touch §5.5.
- **Smoke variant for technician close** — `scripts/smoke.sh` currently demonstrates the
  engineer close-on-behalf path. A second smoke (or a flag in the existing one) could exercise
  the technician close path now that it exists. Cheap; defer until the next smoke iteration.
- **Stage 21 (device tokens) still blocked on FE sign-off** — unchanged from Stage 20.4.
- **Future technician single-detail read** — if the FE wants the same one-round-trip behaviour
  on close that staff get, add `GET /api/v1/technician/complaints/{id}` (assigned-technician
  scope) and switch the close endpoint to return the detail. Defer until FE asks.


### Stage 20.6 — Stage 21 contract frozen (v1.0) — ✅ 2026-06-25

> FE sign-off received. `STAGE_21_DEVICE_TOKEN_CONTRACT.md` flipped from
> `Status: draft for FE sign-off` to `Status: FROZEN — implementing in Stage 21.1`,
> with two deltas folded in. BE Stage 21.1 is now unblocked.

#### Scope delivered

**Doc-only stage.** No production code, no schema, no migration.

- Header status: **draft → FROZEN (v1.0, 2026-06-25)**. Mirror note added pointing at
  the sibling FE copy as the same source of truth.
- **§4 payload** — added `eventOccurredAt` (ISO-8601 IST string, server clock captured at
  `AFTER_COMMIT`). Lets the FE render correct "n minutes ago" labels for batched
  deliveries after the device was offline / killed, and de-dupe against an inbox snapshot
  pulled on resume. Localisation note clarified: English-only in v1 per §9.1 decision;
  schema bumps to `2` when `titleKey` / `bodyKey` / `args` are added in Stage 22.
- **§8 error codes** — reserved two for the FE to start coding against without waiting
  for the cap to land server-side:
  - `INVALID_PUSH_TOKEN_FORMAT` (400) — distinct from `VALIDATION_FAILED` so the FE can
    trigger a "fetch a fresh token and retry once" path on FCM / APNs shape drift.
  - `DEVICE_TOKEN_LIMIT_EXCEEDED` (409) — reserved code only; no cap is enforced in
    Stage 21.1. When a cap is wanted later, no contract bump is needed.
- **§9 — converted from "Open questions" to "Resolved decisions"** as a single table.
  Each row carries the original question, the FE decision, and the FE rationale so the
  trail survives any future contributor asking "why?". Six decisions:
  - 9.1 Localisation: **later** (defer to Stage 22 inbox).
  - 9.2 Web push: **no** in v1; `WEB` enum kept for forward compat.
  - 9.3 Token rotation: **cold start + `onTokenRefresh`**, idempotent refresh path; never
    `DELETE`+`POST`.
  - 9.4 Logout revoke: **staff yes, consumer no** (consumer has no logout button); failed
    `DELETE` is fire-and-forget, never blocks JWT clear.
  - 9.5 Quiet hours: **out of scope** (defer to Stage 22).
  - 9.6 Permission UX: **prompt at logical moments**, not on launch; FE handles deny /
    later-revoke with a `DELETE` only when there's a row to revoke.
- **Additional confirmations** (no contract change required, recorded for trail): device
  storage primitives per platform; multi-account `(principal_kind, device_id)` model;
  SLA breach once-per-breach not per sweep tick; §6.2 never-log list mirrored in FE
  Sentry `beforeSend` in Phase 7.
- **§11 changelog** — entry added marking v1.0 freeze with the two folded deltas.
- **Mirror** — `complaints-frontend/docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md` overwritten
  with the frozen copy. `diff -q` confirms byte-identical.

#### Incidents fixed during implementation

_None — process / coordination entry._

#### Tests added

_None — no production code touched._

#### Build status

- No build / test deltas. Counts unchanged since Stage 20.5: **152 unit + 8 IT**,
  OpenAPI **52 paths**.

#### Carry-overs / known follow-ups (next stage queue)

- **Stage 21.1 — schema + endpoints.** Now unblocked. Scope:
  - Flyway `V1.5__device_token.sql` per §7 (table + partial-unique on
    `(principal_kind, device_id) WHERE active`, indexes per §7).
  - Four REST endpoints: `POST /api/v1/consumer/devices`,
    `DELETE /api/v1/consumer/devices/{deviceId}`,
    `POST /api/v1/staff/devices`, `DELETE /api/v1/staff/devices/{deviceId}`.
  - `DeviceTokenService` + hand-written mapper + DTOs (`DeviceRegistrationRequest`,
    `DeviceTokenResponse`). Idempotent upsert behaviour per §3.1.
  - Five new `ErrorCode` entries: `DEVICE_PLATFORM_UNSUPPORTED`,
    `DEVICE_NOT_OWNED_BY_CONSUMER`, `DEVICE_NOT_OWNED_BY_USER`,
    `INVALID_PUSH_TOKEN_FORMAT`, `DEVICE_TOKEN_LIMIT_EXCEEDED` (last one wired but never
    thrown yet — reserved per §8).
  - Tests per minimum-test policy: 1 happy + 1 unhappy per service method + 1
    `@WebMvcTest` per endpoint cluster.
- **Stage 21.2 — provider + listeners** (after 21.1). `PushService` interface +
  `ConsolePushService` (dev) + `FcmPushService` (prod) + nine
  `@TransactionalEventListener(phase = AFTER_COMMIT)` methods, one per `ComplaintEvent`
  type per §5. SLA breach listener fires once per breach (not per sweep tick) — the
  Stage 20 event is already published only on the flip transition, so this is automatic.
- **Stage 22** — persisted in-app notification inbox + per-user read state + localisation
  bump to `schemaVersion=2`. Independent of Stage 21 once the contract is live.
- **FE side** — `@complaints/i18n` `errorCodes.INVALID_PUSH_TOKEN_FORMAT` and
  `errorCodes.DEVICE_TOKEN_LIMIT_EXCEEDED` keys must be added in the same FE PR that
  consumes the Stage 21.1 OpenAPI snapshot. Tracked in the FE log.
- **Filler work while FE wires Stage 21.1 register flow** — same Phase 7 ops chores list
  as Stage 20.4 (git SHA in `/actuator/info`, JSON log layout, Caffeine metrics) is still
  the right backstop if Stage 21.1 + 21.2 ship faster than the FE can absorb.


### Stage 21.1 — Device token registry (schema + REST surface) — ✅ 2026-06-25

> First Stage 21 code slice. Ships the schema, the four `POST` / `DELETE` endpoints, and
> the persistence-only half of the device-token registry per the frozen contract
> (`STAGE_21_DEVICE_TOKEN_CONTRACT.md` v1.0). Stage 21.2 adds the push provider + the
> nine event listeners on top of this.

#### Scope delivered

**Schema** — new module `notification`:

- `V1.5__create_device_token.sql` — drops the V1.0 placeholder `device_token` (staff-only,
  no `device_id`, no `active`, no XOR — never had any caller / any data) and recreates the
  table in the contract-conformant shape. Partial-unique indexes on
  `(consumer_master_id, device_id) WHERE active` and `(user_id, device_id) WHERE active`;
  fan-out `WHERE active` indexes for the Stage 21.2 listeners; `ck_device_token__principal_xor`
  enforces exactly-one-principal per row. Attaches the V1.3 `set_updated_at()` trigger
  explicitly per the Stage 2.1 carry-over (self-contained migration). Per hard-rule #5,
  V1.0 stays untouched.

**Production code** — all under `com.example.complaints.notification.*`:

- **`model.DeviceToken`** entity (Lombok `@Builder`, all schema columns, DB-managed
  timestamps via `@Generated`) + **`model.DevicePlatform`** enum (`ANDROID`, `IOS`, `WEB`).
- **`repository.DeviceTokenRepository`** — four derived finders (active + any, per
  principal kind). No `@Query` — Spring Data conventions suffice.
- **`dto.DeviceRegistrationRequest`** (record, `@NotBlank` / `@Size` / `@Schema` on each
  component) — `platform` bound as `String` (not the enum) so an unknown value surfaces as
  `DEVICE_PLATFORM_UNSUPPORTED` per §8 rather than a generic Jackson enum-binding 400.
- **`dto.DeviceTokenResponse`** (record) — deliberately omits `pushToken` per §6.2 (never
  echo to avoid FE log / cache leakage). Includes `platform`, `appVersion`, `active`,
  `registeredAt` (IST), `updatedAt` (IST).
- **`mapper.DeviceTokenMapper`** — hand-written per hard-rule #3.
- **`service.DeviceTokenService`** — four methods (`register{ForConsumer,ForUser}`,
  `revoke{ForConsumer,ForUser}`). Register is idempotent upsert: if an active row exists
  for `(principal, device_id)`, refresh in-place (Hibernate dirty-check; `updated_at`
  bumps via V1.3 trigger); else insert. Returns `RegistrationResult(response, created)`
  so the controller can map to 201 vs 200. Revoke is principal-scoped and idempotent:
  missing / already-inactive row → 204 no-op.
- **`controller.ConsumerDeviceController`** — `/api/v1/consumer/devices` (POST, DELETE).
  Uses `@AuthenticationPrincipal VerifiedConsumer caller`; `@SecurityRequirement(name =
  "consumerVerifyToken")` so the OpenAPI snapshot tags it with the right security scheme.
- **`controller.StaffDeviceController`** — `/api/v1/staff/devices` (POST, DELETE).
  Uses `@AuthenticationPrincipal AuthenticatedStaff caller`. Gated by the existing
  `/api/v1/staff/**` → `.authenticated()` matcher + the `JwtAuthFilter` +
  `PasswordResetRequiredFilter` chain.

**ErrorCodes added** (5 — all from contract §8):

- `DEVICE_PLATFORM_UNSUPPORTED` (400) — actively thrown when `platform` parses to an
  unknown value.
- `DEVICE_NOT_OWNED_BY_CONSUMER` (403), `DEVICE_NOT_OWNED_BY_USER` (403) — reserved.
  Unreachable in this implementation because every query is principal-scoped (foreign
  rows are simply invisible) — privacy-stronger than the contract requires. Kept in the
  enum for future cross-principal admin endpoints. Documented inline in
  `DeviceTokenService`.
- `INVALID_PUSH_TOKEN_FORMAT` (400) — reserved per FE sign-off. No provider-shape
  validation in 21.1 (FCM / APNs validate on send in 21.2).
- `DEVICE_TOKEN_LIMIT_EXCEEDED` (409) — reserved per FE sign-off. No cap enforced in 21.1.

**Security wiring**: no `SecurityConfig` change required.
- `/api/v1/consumer/devices/**` falls under the existing `/api/v1/consumer/**` →
  `permitAll` at chain level + `ConsumerVerificationFilter` (Stage 20.3 ensured
  `JwtAuthFilter` skips this prefix via `shouldNotFilter`).
- `/api/v1/staff/devices/**` falls under the existing `/api/v1/staff/**` →
  `.authenticated()` matcher; `JwtAuthFilter` + `PasswordResetRequiredFilter` apply.

#### Incidents fixed during implementation

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | Every IT failed at context load: `PSQLException: relation "device_token" already exists` while applying V1.5. | V1.0 shipped a placeholder `device_token` table back in Phase 0 (staff-only, `user_id NOT NULL`, `token UNIQUE`, `platform VARCHAR(20)`) — a forgotten scaffold. None of the production code references it; it's been dead schema for six months. V1.5 tried to `CREATE TABLE` on top of it. | V1.5 prepended with `DROP TABLE IF EXISTS device_token CASCADE;` before the `CREATE TABLE`. Safe because (a) the placeholder has zero callers and (b) it's empty in every environment (production has never written a row). Per hard-rule #5, V1.0 stays untouched. Migration header comment records the rationale so the next reader doesn't think this was a careless overwrite. |
| 2 | IDE inspector flagged 5 phantom errors: `Could not autowire DeviceTokenRepository` + "never used" warnings on the four service methods + an unresolved Javadoc symbol. | IntelliJ static analysis can't see Spring Data proxies (repos) or runtime-wired controller dependencies, and cross-file Javadoc symbol resolution lags behind file creation order. | Ignored — verified clean via `./mvnw compile` (zero warnings, zero errors). Same class of false-positives we've documented since Stage 1. |

#### Tests added (11 new — total 163 unit + 8 IT)

Minimum-test policy: 1 happy + 1 unhappy per representative behaviour. Two test classes
(one service slice, one controller slice covering both `Consumer*` and `Staff*` —
shape-identical modulo principal).

- `notification/service/DeviceTokenServiceTest` (5):
  - `registerForConsumer_firstTime_created` — first-time path: `created=true`, row
    persisted with consumer FK, `active=true`, push token captured.
  - `registerForConsumer_refresh_inPlace` — same `(principal, device_id)` second time:
    `created=false`, push token overwritten via Hibernate dirty-check, no `save(...)` call
    (proves the refresh path doesn't re-insert).
  - `registerForUser_badPlatform_rejected` — unknown `platform` → `DEVICE_PLATFORM_UNSUPPORTED`,
    repo never touched (early fail before any query).
  - `revokeForConsumer_missing_isIdempotent` — missing row → no-op, no save.
  - `revokeForUser_active_flipsActive` — active row → `active=false` via dirty-check, no save.
- `notification/controller/DeviceControllersTest` (6, `@WebMvcTest` slice covering both
  controllers in one class):
  - `consumerRegister_firstTime_201` — 201 Created envelope; explicit assertion that
    `$.data.pushToken` does not exist (privacy guard).
  - `consumerRegister_refresh_200` — refresh path yields 200 OK (not 201).
  - `consumerRegister_blankDeviceId_400` — `VALIDATION_FAILED`, service untouched.
  - `consumerRevoke_204` — DELETE returns 204 (no envelope).
  - `staffRegister_firstTime_201` — symmetric to consumer; delegates with caller `userId`.
  - `staffRevoke_204` — symmetric to consumer revoke.

#### Build status

```
[INFO] Tests run: 163, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +11 from Stage 20.6)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged set, all still green against V1.5)
[INFO] BUILD SUCCESS
docs/openapi.json — 52 → 56 paths (+4: consumer + staff devices, POST and DELETE each).
```

#### Carry-overs / known follow-ups

- **Stage 21.2** (next slice — push provider + listeners):
  - `PushService` interface + `ConsolePushService` (`@Profile({"dev","test"})` +
    `@ConditionalOnProperty(... matchIfMissing=true)`) that logs the rendered payload at
    INFO so the dev smoke can verify fan-out without FCM credentials.
  - `FcmPushService` (`@ConditionalOnProperty("app.push.provider=fcm")`) using
    `firebase-admin` against a service-account JSON. Dep + CVE check come with this slice.
  - Nine `@TransactionalEventListener(phase = AFTER_COMMIT)` methods, one per
    `ComplaintEvent`, per contract §5. Each composes the payload (data-only message with
    `type`, `ticketNo`, `complaintId`, `title`, `body`, `eventOccurredAt`,
    `schemaVersion`) and fans out to the recipient set's active device tokens with
    per-token failure isolation.
  - One `app.push.never-log-list` config (mirrors §6.2: push token, raw event payload,
    PII never logged) + the corresponding test that asserts the logger is muted.
- **FE-side coordination** — FE can now generate the client (`pnpm api:gen` against the
  refreshed `docs/openapi.json`) and call `register{Consumer,Staff}Device` /
  `revoke{Consumer,Staff}Device`. The `pushToken` field is correctly absent from the
  generated `DeviceTokenResponse` type (so FE can't accidentally cache it). The 200-vs-201
  distinction is on the HTTP response, orval will surface both as success.
- **Smoke harness** — `scripts/smoke.sh` doesn't exercise the device endpoints. Add a
  small step (consumer-register → consumer-revoke; staff-register → staff-revoke) the
  next time we touch the smoke, after Stage 21.2 has visible side effects worth asserting.
- **`@DataJpaTest` for the partial-unique indexes** — skipped per minimum-test policy.
  The boot IT exercises the migration; the contract-level partial-unique guarantee is
  enforced by Postgres at the DB layer (not application code). If we ever discover a real
  bug exploiting the partial-unique semantics, that's the moment to add the slice IT.


### Stage 21.2 — Push provider + AFTER_COMMIT listeners — ✅ 2026-06-25

> Closes the second half of Stage 21. Adds a `PushService` abstraction (with the dev/test
> `ConsolePushService` impl) and nine `@TransactionalEventListener(phase = AFTER_COMMIT)`
> methods that turn the Stage 20 `ComplaintEvent` hierarchy into push notifications per
> the frozen contract §5. Registering a device today now actually receives a push when a
> complaint touches you — end to end through the dev profile.

#### Scope delivered

**Provider abstraction** (`notification.service`)

- `PushService` interface — single method `send(DeviceToken target, PushPayload payload)`.
  Implementations must not log the `pushToken` or `body` per contract §6.2; the caller
  (the listener) owns per-recipient failure isolation per §6.1.
- `ConsolePushService` — `@Profile({"dev","test"})` +
  `@ConditionalOnProperty("app.push.provider"="console", matchIfMissing=true)`. Emits one
  `INFO` log line per send with the §6.2 whitelist: `event`, `ticketNo`, `complaintId`,
  `recipientUserId`, `consumerMasterId`, `platform`, `deviceId`, `outcome=SENT`. Never
  touches the network.
- **`FcmPushService` not shipped this slice.** Deferred to **Stage 21.3** until
  GCP / FCM service-account credentials are provisioned (mirrors the
  Stage 10a → 10c deferral pattern for GCS storage). Until then the `prod` profile cannot
  send real pushes — documented in the carry-overs below.

**Payload rendering** (`notification.service`)

- `PushType` enum — nine values, one per `ComplaintEvent` subtype, matching the contract
  §4 / §5 `type` strings the FE routes on.
- `PushPayload` record — frozen §4 v1 wire shape: `type`, `ticketNo`, `complaintId`,
  `title`, `body`, **`eventOccurredAt`** (server clock at `AFTER_COMMIT`, captured by the
  factory per the §4 delta), `schemaVersion=1`. Includes `toFcmDataFrame()` that renders
  the FCM all-string data frame.
- `PushPayloadFactory` — one builder per event subtype. Templates are English-only per
  FE sign-off §9.1; localisation moves to the Stage 22 inbox row (bump to
  `schemaVersion=2` then). Titles / bodies kept terse and PII-free except for the
  rejection reason on `COMPLAINT_REJECTED` (consumer needs it to understand the rejection).

**Listener** (`notification.service`)

- `ComplaintNotificationListener` — nine
  `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` methods, exactly
  the §5 recipient table:
  - `onSubmitted` → active engineer for the receiving DC.
  - `onAssigned` → assigned technician; **cc engineer only when severity = HIGH** (per §5
    "avoid noise on LOW/MEDIUM").
  - `onReassigned` → new technician + previous technician + engineer.
  - `onResolved` → consumer + engineer.
  - `onClosed` → consumer (nudges to feedback).
  - `onSlaBreached` → assigned technician + assigned engineer. **Once per breach**
    inherited from `SlaMonitorService` only publishing on the `false → true` transition;
    zero local de-dup state required.
  - `onFeedback` → assigned technician + engineer; **admin escalation on rating ≤ 2** via
    a two-hop `DC → subdivision → admin` lookup.
  - `onCancelled` → engineer of the DC (cancellation is `SUBMITTED`-only, so there's
    never an assigned technician in v1 — documented inline).
  - `onRejected` → consumer; rejection reason inlined into the body.
- Fan-out helpers `fanOutStaff(userId, payload)` / `fanOutConsumer(consumerMasterId,
  payload)` query active device tokens via the new repo methods and route each through
  `sendIsolated(...)` which try/catches every `push.send(...)` call. Per §6.1 a single
  bad device never blocks the rest of the fan-out — verified by test.

**Repository additions**

- `DeviceTokenRepository.findByUserIdAndActiveTrue(Long)` and
  `findByConsumerMasterIdAndActiveTrue(Long)` — return `List<DeviceToken>` (one principal
  can register multiple devices: phone + tablet).
- `UserAccountRepository.findFirstByRoleAndSubdivisionIdAndEnabledTrue(...)` — used by
  the admin-on-low-rating escalation path.

**Cross-module service hops**

- `StaffLookupService.findActiveAdminForSubdivision(Long subdivisionId)` — mirrors the
  existing `findActiveEngineerForDc(...)` for the feedback escalation.
- Existing `DistributionCenterService.getSubdivisionId(Long)` reused for the
  DC → subdivision hop.
- All cross-module calls go through `*Service` interfaces, never another module's
  repository — ArchUnit `PackageBoundaryTest` still green (5/5).

#### Incidents fixed during implementation

_None of note._ The Stage 20 event records had every field the listener needed
(thanks to Stage 20's "primitive IDs only, no JPA entities" design), so no event-shape
changes were required this stage. ArchUnit passed first try because the new
service-to-service hops (`notification.service` → `auth.service.StaffLookupService` and
→ `masterdata.service.DistributionCenterService`) are exactly what the boundary rules
explicitly allow.

#### Tests added (12 new — total 175 unit + 8 IT)

`notification/service/ComplaintNotificationListenerTest` — covers each of the 9
listeners plus the two cross-cutting concerns flagged by §5 / §6.1:

- `onSubmitted_findsEngineerForDc` — DC → engineer lookup + fan-out.
- `onAssigned_lowSeverity_noEngineerCc` — LOW / MEDIUM: tech only, engineer never queried.
- `onAssigned_highSeverity_engineerCcd` — HIGH: tech + engineer both notified;
  `ArgumentCaptor` asserts the payload `type=COMPLAINT_ASSIGNED` + body contains "HIGH".
- `onReassigned_threeRecipients` — new tech + previous tech + engineer all fetched.
- `onResolved_consumerAndEngineer` — both principals' device lists fetched.
- `onClosed_consumerOnly` — consumer fan-out only; **asserts no staff lookup** (would
  be a bug per §5).
- `onSlaBreached_techAndEngineer` — both staff principals notified.
- `onFeedback_highRating_noAdminEscalation` — rating > 2: admin lookup never fires.
- `onFeedback_lowRating_adminEscalated` — rating ≤ 2: full DC → subdivision → admin
  two-hop fires + admin's devices fetched.
- `onCancelled_engineerOfDc` — engineer-of-DC fallback (no assigned tech on SUBMITTED).
- `onRejected_consumerWithReason` — rejection reason inlined into body.
- `perRecipientIsolation_failureDoesNotBlockOthers` — `push.send(bad)` throws
  `RuntimeException` → does NOT propagate; the next device still receives its push.
  Verifies §6.1 contractually.

Existing `ComplaintEventLogger` (Stage 20's debug-only listener) kept in place — runs
alongside the real notification listener; its INFO line gives event-level visibility
while the push service gives per-recipient visibility. Different granularity, not
redundant.

#### Build status

```
[INFO] Tests run: 175, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +12 from Stage 21.1)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged set, all green)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged at 56 paths (Stage 21.2 ships no HTTP surface; pure internal eventing).
```

ArchUnit 5/5 green — confirms the new cross-module hops
(`notification.service → auth.service`, `notification.service → masterdata.service`) stay
on the allowed side of the slice rules.

#### Carry-overs / known follow-ups

- **Stage 21.3 — `FcmPushService` + Firebase Admin SDK** (was Stage 21.2's "FCM impl"; split
  out the same way Stage 10c carries the GCS impl):
  - Add `com.google.firebase:firebase-admin` dependency. Run `validate_cves` on the chosen
    version before merge.
  - `@Service` + `@Profile("prod")` + `@ConditionalOnProperty("app.push.provider"="fcm")`.
  - `FcmProperties` (`@ConfigurationProperties("app.push.fcm")`) — service-account JSON
    path, project id.
  - Permanent-failure handling per §6.1: on `NotRegistered` / `InvalidRegistration` /
    `MismatchSenderId`, flip the offending `device_token` row `active=false` in a fresh
    `REQUIRES_NEW` transaction so one bad token doesn't poison the fan-out.
  - Defer until GCP / FCM service-account credentials are provisioned.
- **Stage 21.3 — `SMS fallback for unregistered consumers` on resolve** — per §5 note.
  Opt-in behind `app.push.sms-fallback-on-resolve=true`, off by default. Reuses the
  MSG91 wiring tracked since Stage 9.
- **Smoke harness coverage** — `scripts/smoke.sh` doesn't exercise the new fan-out. A
  small extension can: register a consumer device → submit + assign + resolve + close →
  grep `$APP_LOG_FILE` for `[PUSH] outcome=SENT event=COMPLAINT_*` lines proving every
  listener fired. Cheap; add the next time we iterate the smoke.
- **Stage 22 — persisted in-app notification inbox** — same payload shape on the wire,
  but each push also writes a `notification` row keyed by recipient principal with a
  per-user `read=false` state. Independent of Stage 21.3; can start any time the FE
  signs off on a separate Stage 22 contract.
- **Cache the engineer-per-DC + admin-per-subdivision lookups** — `findActiveEngineerForDc`
  and `findActiveAdminForSubdivision` are read on every event firing. Caffeine cache by id
  with a short TTL is the obvious next step if profiling shows hotspots. Deferring until
  we have signal — premature optimisation otherwise.
- **`@TransactionalEventListener` failure during AFTER_COMMIT** — if the listener
  itself throws (not just `push.send`), Spring swallows the exception silently. Today
  we rely on the per-`sendIsolated` catch to absorb everything that could plausibly
  fail. If we ever add code paths that can throw before reaching that helper (e.g. a
  repo timeout on the staff lookup), wrap the listener body in a try/catch too. Tracked
  but not actioned — current code paths can't throw before isolated `send` runs.
- **Localisation bump to `schemaVersion=2`** — when Stage 22's inbox ships and the FE
  starts rendering localised copy from the inbox row (not the push frame), bump
  `PushPayload.CURRENT_SCHEMA_VERSION` to 2 and add `titleKey`, `bodyKey`, `args`
  fields. Per FE sign-off §9.1 this is a coordinated Stage 22 change, not Stage 21.x.


### Stage 21.2.1 — Stable `operationId` annotations across every controller — ✅ 2026-06-25

> FE post-Stage-21.1 feedback flagged a recurring tax: every stage that adds a new
> controller with a generic `@Operation` summary (`create`, `list`, `close`, `register`,
> `revoke`) reshuffles orval's un-suffixed slot, so the FE eats a one-line barrel-alias
> edit + comment refresh on every regen. Stage 21.1 itself bit them with
> `useClose → useClose1` and `useList2 → useList3`. This stage closes the orval-suffix
> carry-over that's been tracked since **Stage 7**.

#### Scope delivered

- Added explicit `operationId = "verbNounNoun"` to every `@Operation` annotation in
  every controller — **61 endpoints** across **15 controller classes**. Naming convention:
  intent-revealing camelCase verbs (`list*`, `get*`, `create*`, `update*`, `activate*`,
  `deactivate*`, `assign*`, `submit*`, `resolve*`, `close*`, `cancel*`, `register*`,
  `revoke*`, `search*`, `reset*`, `send*`, `verify*`, `change*`, `logout*`, `login*`,
  `mark*`, `start*`, `add*`, `refresh*`) followed by the noun phrase that scopes them.
- A handful of endpoints had no `@Operation` at all (e.g. `getSubdivision`,
  `updateCategory`, the activate / deactivate twins on masterdata admin controllers).
  Added minimal `@Operation(operationId = "...", summary = "...")` on those 11 too so
  they don't fall back to `operationId = "deactivate2"` or similar.
- **Disambiguation** for endpoints that share a verb across roles:
  - `closeComplaint` (engineer / admin on-behalf) vs `closeComplaintAsTechnician` (the
    Stage 20.5 normal closer).
  - `getStaffDirectoryEntry` (single) vs `getStaffDirectoryEntries` (batch) vs
    `searchStaffDirectory` (paged search) — all on the same URL with different `params`
    discriminators.
  - `submitFeedback` (POST) vs `getFeedback` (GET) on the same path.
  - `registerConsumerDevice` / `revokeConsumerDevice` vs
    `registerStaffDevice` / `revokeStaffDevice` — same shape, different principal.
- Springdoc emits each `operationId` verbatim into `docs/openapi.json`; orval
  consumes that to name the generated hook
  (`useRegisterConsumerDevice` instead of `useRegister`). Across stages the names now
  stay stable regardless of declaration / scan order, so the FE barrel alias edits go
  away.
- Verified end-to-end: re-snapshotted `docs/openapi.json` after
  `./mvnw verify`, parsed every `paths.*.<verb>.operationId`, asserted **0
  numeric-suffix smells** across all 61 operations.

#### Mechanics

- Mapping table + Python script (`/tmp/add_op_ids.py`) anchored on each annotation's
  summary substring to inject `operationId` as the first arg of the `@Operation(...)`
  block — works for both single-line and multi-line forms. The 6 multi-line annotations
  that mismatched on first pass (different exact wording) were fixed in a second pass
  with corrected anchors. Total 62 controller-side edits, no manual per-file fiddling.
- The script + mapping table are throwaway; not committed.

#### Incidents fixed during implementation

_None._ Annotations are additive; existing tests all still passed first try.

#### Tests added

_None._ The operationId is metadata for the OpenAPI emit + FE codegen — no runtime
behaviour to test. The `OpenApiExportIT` re-snapshot validates the change end-to-end
by writing `docs/openapi.json` with the new IDs; the spec-drift CI guard (still tracked
for Phase 7) will catch any future regression on a per-PR basis.

#### Build status

```
[INFO] Tests run: 175, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; unchanged)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged, all green)
[INFO] BUILD SUCCESS
docs/openapi.json — 56 paths unchanged; every operation now carries a stable, intention-revealing operationId.
```

#### Carry-overs / known follow-ups

- **Stage 21.2 fan-out heads-up for FE** — recorded as a separate todo in the FE-handoff
  prompt for the next deploy: once `notification.service` is live in a shared dev env,
  the FE should register a real device on that env and trigger a complaint event to
  verify the §4 payload frame matches the mock they've been building against. Cheap
  catch for any subtle wire divergence before Stage 21.3 (FCM impl) ossifies around the
  console-mode console log shape. **Today the BE only runs locally**, so this is a
  "do it the moment we have a shared deploy target" item, not actionable now.
- **Closes the Stage 7 carry-over**: *"Orval numeric-suffix aliases (`create_1`,
  `deactivate_2`, etc.) remain positionally brittle. Real fix is on the BE side — add
  explicit `operationId = "createSubdivision"` (etc.)"*. ✅ closed.
- **Spec-drift CI guard** still tracked from Stage 3 / Stage 6 / Stage 7. With stable
  operationIds in place the diff signal-to-noise on `docs/openapi.json` is much higher —
  the guard becomes proportionally more valuable.


### Stage 21.2.2 — Nightly stale-device-token sweep — ✅ 2026-06-25

> Closes the Stage 21.2 carry-over *"nightly stale-token sweep"*. Long-idle
> `device_token` rows are flipped to `active = false` so the fan-out
> (`ComplaintNotificationListener`) stops targeting devices that have not refreshed in
> a while — uninstalled app, OS-revoked permission, killed-and-never-relaunched. Pure
> backend, zero FE coordination, no new endpoint, no contract change.

#### What shipped

- **`notification.DeviceTokenSweepProperties`** — `record` bound from
  `app.device-token-sweep.*` with two fields: `inactivityDays` (default 60) and
  `enabled` (default true; kill-switch). Defensive ctor clamps `inactivityDays <= 0`
  back to 60 — fail-soft to production-sane.
- **`notification.service.DeviceTokenSweepJob`** — `@Scheduled(cron = "0 30 3 * * *",
  zone = "Asia/Kolkata")`. Off-peak 03:30 IST. Computes
  `cutoff = now() − inactivityDays` and calls the new bulk-update repo method.
  Logs at `INFO` only when rows were actually swept (zero-noise on quiet nights);
  `DEBUG` when the kill-switch suppresses the run.
- **`DeviceTokenRepository.markInactiveOlderThan(Instant)`** — `@Modifying`
  bulk-update query: `update DeviceToken d set d.active = false where d.active = true
  and d.updatedAt < :cutoff`. Returns row count for the log line. Bypasses the
  persistence context so we don't load + dirty-check potentially thousands of rows.
- **`application.yml`** — `app.device-token-sweep.inactivity-days: 60` +
  `enabled: true` with inline comment explaining what the sweep does. Mirrors the
  same pattern as `app.complaint.*` / `app.otp.*`.
- **`ComplaintsApplication`** — added `com.example.complaints.notification` to
  `@ConfigurationPropertiesScan` so the new record gets picked up.
- **Tests**: `DeviceTokenSweepJobTest` — 2 tests per the minimum-test policy.
  - Happy: cutoff is `now − 60d` and `markInactiveOlderThan` gets called once;
    captured `Instant` is asserted to sit inside `[before − 60d, after − 60d]` to
    cover the small wall-clock delta between the two `Instant.now()` calls.
  - Kill-switch: when `enabled = false` the job logs at DEBUG and the repo is never
    touched (`verify(repo, never()).markInactiveOlderThan(any())`).

#### Why `updated_at` (not `created_at`) is the right idleness signal

The V1.3 trigger refreshes `device_token.updated_at` on every register/refresh per
contract §3.1's idempotent-upsert pattern. So `updated_at` is effectively
*"last time FE proved the token still works"* — which is exactly the lifetime signal
the sweep needs. `created_at` would only tell us when the device first registered,
which is useless once a heavily-used phone has been refreshing daily for months.

#### Why bulk-update instead of `findAll → set → save`

A subdivision with O(10k) active device rows would force Hibernate to materialise
each row, dirty-check it, and emit one `UPDATE` per row — defeating the point of a
nightly batch job. The single `@Modifying` query collapses the whole sweep into one
SQL statement. The trade-off is that any first-level cache entry for those rows would
go stale within the same transaction, but the job has no other reads/writes around
the bulk update so this is a non-issue.

#### Why the kill-switch

Three reasons it earns its keep beyond the "you should always have one" reflex:

1. **Ops emergency lever** — if the sweep misbehaves on prod we can disable it via a
   config change + redeploy rather than reverting code.
2. **Sandbox dev environments** — for FE smoke tests against a fresh DB we may want
   tokens to never expire so the same device row survives long demo cycles.
3. **Test isolation** — defaults to `true` in `application.yml`, but a future test
   profile yml can flip it to `false` if any IT proves flaky around the 03:30 cron.

#### Tests added

- `DeviceTokenSweepJobTest` — 2 tests (happy + kill-switch). No IT: this is a single
  JPQL bulk-update query — Spring Data + Hibernate's JPQL emitter is already
  exercised by hundreds of existing queries; an IT here would only re-prove that the
  framework works.

#### Build status

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +2 vs Stage 21.2.1)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged, all green)
[INFO] BUILD SUCCESS
docs/openapi.json — 56 paths, unchanged (sweep is internal; no endpoint added).
```

#### Carry-overs / known follow-ups

- **Stage 21.3 BE — real `FcmPushService`** is the next push-stack step. **Gated on
  GCP service-account JSON being provisioned for staging.** Until creds land,
  `ConsolePushService` continues to exercise the listener pipeline on dev. Heads-up
  ping to FE flagged this.
- **Spec-drift CI guard** continues to ride (Stage 3 / 6 / 7 / 21.2.1 carry).
- **SMS fallback for tokenless consumers** still parked per FE alignment — Stage 22+
  decision once the persisted-inbox row exists to drive routing.
- Sweep currently runs against an empty `updated_at` column index — performance is
  fine while the table is small. If `device_token` grows past O(100k) rows on prod,
  consider `CREATE INDEX ix_device_token_updated_at_active ON device_token (updated_at)
  WHERE active` in a follow-up migration. Tracked, not blocking.


### Stage 21.2.3 — Contract patch v1.0.1 (WEB-platform clarification) — ✅ 2026-06-25

> Doc-only patch. FE caught two ambiguities in the Stage 21.2.2 update prompt:
> (a) my "Stage 21.3 staff side (apps/web)" wording contradicted §9.2's "no web push
> in v1", and (b) the contract never said what happens server-side if FE POSTs a
> register with `platform: "WEB"`. Both resolved in the contract, no code change.

#### What shipped

- **`docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md` → v1.0.1.** Header version bumped,
  §2.3 gained a "WEB platform behaviour in v1" block, §11 versioning line updated,
  changelog entry added at the top. Mirrored byte-identical to
  `complaints-frontend/docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md` (`diff -q` clean).

#### The decisions documented

1. **Register with `platform: "WEB"` is accepted (201/200), no shape diff.** We do not
   reject. Reasoning: rejecting would create a runtime-vs-contract mismatch with §2.3
   keeping `WEB` in the enum and force FE to gate the call on
   `platform !== "WEB"`, which leaks the v1 push-stack decision into FE code.
2. **A registered WEB row is a no-op recipient until Stage 22+** adds a web-push
   provider. The listener iterates active rows and dispatches via `PushService`; no
   v1 `PushService` impl delivers to WEB, so the row sits silent — same outcome as a
   mobile token FCM has marked inactive.
3. **`INVALID_PUSH_TOKEN_FORMAT` stays scoped to actual shape failures**
   (truncated FCM/APNs tokens). It is *not* a "you sent WEB platform" police code —
   that is a deferred-feature decision, not an input-validation failure.
4. **Staff web portal (`apps/web/staff`) has no push surface in v1** and should not
   call `registerStaffDevice` at all. Staff push lands on `apps/mobile` only. Fixes
   the wording bug in the Stage 21.2.2 BE → FE prompt.

#### Why a doc patch and not a full version bump

No wire change, no DB change, no error-code change. Behaviour was already shipped
correctly in Stage 21.1 (the register surface doesn't differentiate by platform) and
Stage 21.2 (the fan-out has no WEB branch); we just hadn't written down *why*. A v1.1
bump would over-signal a semantic change that isn't there. v1.0.1 is the honest
version label for "doc-only clarification of behaviour already frozen".

#### Tests added

_None._ No code changed. The behaviour described in the patch is already covered by
the existing `DeviceTokenServiceTest` happy-path tests, which don't check the platform
value at register time.

#### Build status

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; unchanged)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 56 paths unchanged.
```

#### Carry-overs / known follow-ups

- **Stage 21.3 BE — real `FcmPushService`** still gated on GCP service-account JSON
  for staging. Unchanged from Stage 21.2.2.
- **Web push design (§9.2 reopen)** lands no earlier than Stage 22+ alongside the
  persisted-inbox row. When it does, the work is:
  (a) VAPID key pair + service-worker push subscription on FE,
  (b) a `WebPushService` impl on BE (probably `web-push` Java port or direct VAPID
  HTTP push), and
  (c) wire `WEB` rows into the listener fan-out. The contract's §2.3 already accepts
  WEB rows, so no additive migration is needed at the schema layer.


### Stage 21.2.4 — Spec-drift CI guard — ✅ 2026-06-25

> Closes the **Stage 3 / 6 / 7 / 21.2.1 carry-over** *"spec-drift CI guard"*. Now
> that operationIds are stable (Stage 21.2.1), every change to a controller / DTO /
> `@Operation` that hits the wire either lands with a regenerated `docs/openapi.json`
> in the same PR — or CI fails. No more silent drift between the BE shape and the
> snapshot the FE codegen reads.

#### What shipped

- **`OpenApiExportIT` reshaped** from "always overwrite" to **verify-by-default /
  opt-in-update**:
  - **Verify mode (default + CI):** fetches `/v3/api-docs` from a Spring Boot test
    server, compares string-equal against the committed `docs/openapi.json`. On
    mismatch, fails with the **byte offset, both sides of the divergence in a
    ±60-char window, and the exact regenerate command** to fix it.
  - **Update mode (`-Dopenapi.update=true`):** old behaviour — overwrites
    `docs/openapi.json`. Use locally whenever you intentionally changed a
    controller / DTO / `@Operation`.
- **`CONTRIBUTING.md` "When you change…" table updated.** The "API endpoint" row
  now says explicitly: regenerate the snapshot with
  `./mvnw verify -Dopenapi.update=true`, commit the resulting `docs/openapi.json`
  alongside the controller change, CI fails on drift.

#### Why string-equal (no JSON canonicalisation)

I originally wrote a Jackson-3 canonicaliser (sorted keys + indented). It didn't
work the way I expected — `ORDER_MAP_ENTRIES_BY_KEYS` doesn't apply when you
deserialise to `Object.class` (Jackson gives you a `LinkedHashMap`, insertion-order
preserved). I could have switched to a recursive `TreeMap` rebuild, but stepped
back and looked at the evidence: springdoc has emitted byte-identical output
across all 21 stages of this project for the same controllers. Adding a
non-trivial normaliser to defend against a problem we have zero examples of is
speculative work. The simpler comparison is good enough today. If springdoc ever
goes non-deterministic (e.g. unrelated dependency upgrade), we add the normaliser
then with a concrete failure to anchor against — not before.

#### Why opt-in update mode (and not e.g. a `-Pupdate-spec` Maven profile)

A system property is the lowest-ceremony option. `-Dopenapi.update=true` runs the
same `OpenApiExportIT` you already know — no new lifecycle, no profile activation,
no per-environment override. CI never sets it; local devs set it deliberately when
they change endpoints. One mental model, two behaviours.

#### How it caught itself

Verified by tampering: changed `"closeComplaint"` → `"closeComplaintTAMPERED"` in
`docs/openapi.json`, ran `./mvnw failsafe:integration-test -Dit.test=OpenApiExportIT`,
got:

```
java.lang.AssertionError:
OpenAPI snapshot drift detected — committed docs/openapi.json does not match the live spec.

First divergence:
  at offset 10311 (expected length 61172, live length 61164)
    committed ...without a follow-up GET.","operationId":"closeComplaintTAMPERED","parameters":[...
    live      ...without a follow-up GET.","operationId":"closeComplaint","parameters":[{"name":...

Regenerate with:
  ./mvnw verify -Dopenapi.update=true
then commit docs/openapi.json alongside your controller change.
```

Exactly the signal we want: name of the operation that diverged, both sides, one
copy-paste fix.

#### What bit us during the change

`./mvnw -q failsafe:integration-test -Dit.test=...` does **not** trigger a Java
recompile. The first three "verify mode" runs reported pass — because the
compiled `.class` was still the old write-only version. The drift test only worked
correctly after an explicit `./mvnw test-compile` to refresh the class. **Lesson
for future contributors:** when iterating on test code, use `./mvnw verify` or
prefix with `test-compile`. Logged here so the next person doesn't burn 20 minutes
on the same trap.

#### Tests added

_None._ The IT *is* the guard — it has one test, `exportOrVerifyOpenApiSnapshot()`,
that's now load-bearing on every CI run. No second test would add signal.

#### Build status

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; unchanged)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — 56 paths, unchanged.
```

#### Carry-overs / known follow-ups

- **Stage 3 / 6 / 7 / 21.2.1 carry-over "spec-drift CI guard"**: ✅ closed.
- **Stage 21.3 BE — real `FcmPushService`** still gated on GCP service-account JSON
  for staging. Unchanged.
- **`unusedHintForFutureCanonicaliser()` stub** in the IT is intentional — a one-line
  doc hook so the next contributor who hits a non-deterministic springdoc emit knows
  exactly where the normaliser lives. If after a year nobody has needed it, delete.


### Stage 21.2.5 — Phase 7 ops chores batch (git SHA in /actuator/info + JSON logs + Caffeine metrics) — ✅ 2026-06-25

> Three small ops-readiness items shipped as one stage because they share a theme
> ("observability primitives we should have had since Phase 0") and none of them
> warranted its own log entry. All three were tracked as Phase 7 carry-overs.

#### What shipped

**1. `/actuator/info` now serves git + build identity**

- Added `git-commit-id-maven-plugin` (`io.github.git-commit-id` v9.0.1) bound to
  the `initialize` phase, writing `target/classes/git.properties`. Configured
  tolerant (`failOnNoGitDirectory=false`) so tarball / CI-cache builds without
  a `.git` directory don't break.
- Added `build-info` execution to `spring-boot-maven-plugin`, writing
  `target/classes/META-INF/build-info.properties`.
- `application.yml` `management.info.build.enabled=true` and `management.info.git.enabled=true` with `mode: full` — Boot 4.1 disables these by default, opt-in needed.
- Verified live: `GET /actuator/info` returns:
  ```jsonc
  {
    "git":   { "commit": { "id": { "full": "7121858…", "abbrev": "7121858" },
                           "user": { "name": "Sunil Gulhane" },
                           "time": "2026-06-25T04:54:53Z" },
               "branch": "main" },
    "build": { "artifact": "complaints", "name": "complaints",
               "time": "2026-06-25T05:00:16Z", "version": "0.0.1-SNAPSHOT",
               "group": "com.example" }
  }
  ```
- Only the 5 git fields we care about are emitted (`includeOnlyProperties` filter)
  — full git metadata is noisy (remote URL, dirty flag, tags, build host) and would
  bloat the `info` payload without value.

**2. JSON log layout for prod (Spring Boot 4.1 native structured logging)**

- `application-prod.yml` `logging.structured.format.console: ecs`. ECS = Elastic
  Common Schema, line-delimited JSON to stdout. Kibana / Filebeat / OpenSearch /
  Datadog / GCP Cloud Logging all accept it as-is.
- Dev profile keeps the human-readable Logback default — same logs you've been
  reading for 21 stages, no change to local DX.
- Rotating file appender (when configured at the JVM level) stays human-readable
  for ssh-into-the-box debugging. Stdout-only structured was a deliberate choice.

**3. Caffeine cache metrics → Micrometer / Prometheus**

- Added `io.micrometer:micrometer-registry-prometheus` to the runtime classpath.
  We've been exposing `/actuator/prometheus` since Phase 0 but the underlying
  registry was missing — the endpoint silently returned 404 / empty. **Now live.**
- `CaffeineCacheConfig.recordStats()` was already called; added a comment flagging
  it as load-bearing for the binding so a future "let me just drop this unused call"
  PR doesn't silently zero the metrics.
- Boot's `CacheMetricsAutoConfiguration` does the binding — zero code on our side.
- Verified live against `GET /actuator/prometheus`:
  ```
  cache_gets_total{cache="categories",result="hit"}             0.0
  cache_gets_total{cache="categories",result="miss"}            0.0
  cache_gets_total{cache="subdivisions",result="hit"}           0.0
  cache_gets_total{cache="subdivisions",result="miss"}          0.0
  cache_gets_total{cache="distributionCenters",result="hit"}    0.0
  cache_gets_total{cache="distributionCenters",result="miss"}   0.0
  cache_puts_total{cache="…"}              cache_evictions_total{cache="…"}
  cache_size{cache="…"}                    cache_eviction_weight_total{cache="…"}
  ```
- One meter per cache × stat. Once a Grafana dashboard binds against this we'll see
  hit-rate / eviction-rate per cache out of the box.

#### Tests added

- **`CacheMetricsIT`** — 1 IT that boots the context and asserts
  `cache.gets` meters exist for every configured cache name. Guards the chain
  *(CaffeineCacheManager bean → `setCacheNames(...)` → `recordStats()` →
  `CacheMetricsAutoConfiguration` → `MeterRegistry` binding)*. If any link breaks
  in a future refactor, this fails loudly instead of silently returning empty
  Prometheus output in prod.
- No test for `/actuator/info` — it's declarative config; verifying it would
  re-test Spring Boot's `GitInfoContributor` / `BuildInfoContributor`, which is
  framework code. Manual smoke (`curl /actuator/info`) is the right level.
- No test for the ECS log layout — only active under prod profile, depends on
  Logback init order, and Spring Boot ships its own tests for the encoder. A
  brittle "assert log line is valid JSON" test would protect nothing.

#### Why bundle three changes in one stage

Each is ~30 min of work. Splitting them as 21.2.5 / 21.2.6 / 21.2.7 would triple
the ceremony for no signal. They share one commit per the SRP-for-commits rule
("one commit = one change you might want to revert as a unit") because reverting
"ops observability v1" as a single block makes sense; reverting just the git
plugin alone never would.

#### Build status

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; unchanged)
[INFO] Tests run:   9, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   +1 cache metrics)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged (no controller / DTO change).
```

#### Carry-overs / known follow-ups

- **Phase 7 chores list** — git SHA ✅, JSON logs ✅, cache metrics ✅. Remaining Phase
  7 items beyond these three live in `ROADMAP.md` — none blocking.
- **JVM / HTTP / DB pool metrics** are auto-bound by Boot but **dashboards** aren't
  defined yet. That's an ops repo concern, not BE code.
- **Stage 21.3 BE** still gated on GCP service-account JSON.
- The `prometheus` endpoint was exposed but non-functional from Phase 0 through
  Stage 21.2.4 — anyone reading dashboards for the past 21 stages was reading air.
  Flagged here so it lands in the post-mortem of any "where did our prod cache
  numbers come from?" question. (Answer: nowhere. They start counting today.)


### Stage 21.2.6 — ArchUnit shape rules audit + four new rules — ✅ 2026-06-25

> Roadmap cross-cutting-concerns table said *"Flip `failOnEmptyShould=true` after
> Phase 1"*. Audit showed it was **already** flipped in Phase 1 Stage 2 and the
> roadmap entry was stale. Pivoted the task to *"audit which hard rules from
> `.github/copilot-instructions.md` are still un-encoded and add the high-signal
> ones, given that the strict-empty mode lets us add rules safely."*

#### What shipped

Four new ArchUnit rules in `PackageBoundaryTest` (plus one anchor for the
`fields()` rule):

| Rule | Hard-rule source | Current matches |
|------|------------------|-----------------|
| `dtos_must_be_records_or_enums` | "DTOs are Java 21 `record` types" | 20/20 DTOs are records, 0 violations |
| `mappers_must_end_with_mapper_suffix` | Naming: `*Mapper` suffix when type fits role | 6/6 mappers comply |
| `repositories_must_be_spring_data_interfaces` | Defensive: repo package = Spring Data only | 13/13 repos are `Repository` interfaces |
| `no_field_injection_via_autowired` | "Constructor injection only — no `@Autowired` on fields" | 0 `@Autowired` fields anywhere |
| `field_universe_is_non_empty` (anchor) | n/a | Proves `fields()` universe is non-empty so the rule above can't silently pass on an empty match-set |

Total ArchUnit rules: **5 → 10**.

#### Why the anchor rule

`archRule.failOnEmptyShould=true` covers `classes()`-based rules — ArchUnit fails
loudly if the matched class-set is empty. But the `fields()` API doesn't get the
same automatic protection: if a future package rename moved everything out of
`com.example.complaints..`, the `no_field_injection_via_autowired` rule would
silently pass on an empty field-set. The anchor rule simply asserts the `fields()`
universe is non-empty for our package, so any disappearance of all fields fails the
build instead of going green for the wrong reason.

#### Why not encode more rules

I considered five more candidates and rejected them:

1. **"No `Optional` as field / parameter / in collections"** — ArchUnit can express
   this but the false-positive surface is wide (e.g. `Optional<T>` as a stream
   intermediate). The 5 minutes saved by encoding is dwarfed by the inevitable
   "why is my legit code red?" debugging. Code review covers it cleanly.
2. **"No `catch (Exception)` outside `GlobalExceptionHandler`"** — ArchUnit's
   exception-catching predicates are surprisingly weak; the rule would need a
   per-method byte-code walk. Spotbugs would do this better.
3. **"Service methods ≤ 30 lines / class ≤ 300 lines"** — checkstyle territory,
   not architecture. ArchUnit can do it but the rule lives at the wrong layer.
4. **"Cross-module data exchange via DTOs only, never entities"** — partially
   covered by `controllers_must_not_serialize_entities`. The full rule (service
   methods returning entities to other modules) is hard to express without
   false-positives on same-module service-to-service calls.
5. **"Entities use Lombok"** — Lombok annotations are class-retention but easy to
   miss; better caught by review or a custom checkstyle rule.

Each one is one PR away if we ever feel the pain. The four shipped today are the
ones where the rule was strictly mechanical and currently 100% green.

#### Tampering verification

Added `TamperBean` with `@Autowired private String injected;`, ran the test,
got the expected failure:

```
[ERROR] Tests run: 10, Failures: 1
[ERROR] no_field_injection_via_autowired -- FAILURE!
Field <com.example.complaints.common.TamperBean.injected> is annotated
with @Autowired in (TamperBean.java:0)
```

Removed the file, suite returned to `Tests run: 10, Failures: 0`. Rule has bite.

#### Bonus catch — ROADMAP stale

The cross-cutting-concerns table in `ROADMAP.md` still said *"Flip
`failOnEmptyShould=true` after Phase 1"*. Updated to reflect reality:
already flipped since Phase 1 Stage 2, shape rules added in Stage 21.2.6.

#### Tests added

_None._ The four new `@ArchTest` fields **are** the tests. No second-order test
would add signal — the tamper-verification I did manually is the only sensible
proof these rules work, and it's not the kind of thing you commit (it requires
adding a violating file you then delete).

#### Build status

```
[INFO] Tests run: 182, Failures: 0, Errors: 0, Skipped: 0  (Surefire — unit; +5 ArchUnit rules)
[INFO] Tests run:   9, Failures: 0, Errors: 0, Skipped: 0  (Failsafe — IT;   unchanged)
[INFO] BUILD SUCCESS
docs/openapi.json — unchanged (no controller / DTO change).
```

#### Carry-overs / known follow-ups

- The five rule candidates I rejected above are documented for any future
  contributor who wants to revisit them with concrete pain to anchor against.
- ROADMAP cross-cutting-concerns table now accurate; same audit could be run on
  other stale rows there (none jumped out today).


## How to update this log

1. At the end of a stage, append (or fill in) the corresponding subsection.
2. Keep entries terse. **What shipped**, **what bit us**, **what we tested**, **what we deferred**.
3. Don't rewrite history — additive only. If we have to undo something, add a new entry that says so.
4. Cross-reference TECHNICAL_DESIGN / BRD section numbers where relevant, so a reader can jump to the design context.

