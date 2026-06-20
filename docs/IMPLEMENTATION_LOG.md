# Implementation Log

> Living record of what has actually shipped, per phase / per stage. Update at the
> end of every stage. **Phases and stages here track [`ROADMAP.md`](ROADMAP.md).**
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
- **Frontend** (`complaints-frontend/`): pnpm + Turborepo workspaces, `apps/web` (React 19 + Vite 6 + TS), `packages/{api,i18n,ui-tokens,utils}` stubs, `@complaints/utils` exporting `IST_TIMEZONE` + `formatIstDateTime`, Vite dev-proxy to `/api`, `.github/copilot-instructions.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `AGENTS.md`, `CONTRIBUTING.md`.
- **Build status**: backend `./mvnw verify` green; frontend `pnpm --filter web build` 195 KB JS / 61 KB gzipped (under 180 KB budget).

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

- **`archunit.properties` → `failOnEmptyShould=true`** — flip once Phase 2 module is in (so empty rules are caught as bugs).
- **Repository IT slice** — add a small `@DataJpaTest`-driven IT when the first non-derived query lands (Phase 2 specs).
- **OpenAPI security scheme** — declare `bearerAuth` in `OpenApiConfig` so Swagger UI surfaces an "Authorize" button. Defer until first protected endpoint is exercised manually.
- **CHANGE_PASSWORD audit** — listener will be added with the `audit` module (Phase 7 per ROADMAP). For now, `lastLoginAt` is updated on successful login.
- **Bootstrap admin row** — `fullName` is hard-coded to `"Bootstrap Admin"`; first thing the real admin will do is change password (forced) and edit profile (Phase 2 admin-user-mgmt).

---

### Stage 2 · Backend `masterdata` module — ☐ not started

_(To be filled in when stage lands.)_

---

### Stage 3 · Frontend `packages/api` (orval codegen) — ☐ not started

---

### Stage 4 · Frontend `apps/web` (staff login + master-data screens) — ☐ not started

---

## How to update this log

1. At the end of a stage, append (or fill in) the corresponding subsection.
2. Keep entries terse. **What shipped**, **what bit us**, **what we tested**, **what we deferred**.
3. Don't rewrite history — additive only. If we have to undo something, add a new entry that says so.
4. Cross-reference TECHNICAL_DESIGN / BRD section numbers where relevant, so a reader can jump to the design context.

