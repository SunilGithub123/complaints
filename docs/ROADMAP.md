# Delivery Roadmap

> Phased plan for getting the Complaint Resolution System from scaffold to production.
> Living document — tick off as we go; re-order when reality demands it.
>
> **Sequencing principle:** every phase ends with a *demoable, deployable* increment. Backend ships its OpenAPI spec; the matching FE phase consumes it.

---

## Phase 0 — Scaffolds & guardrails  ✅ **DONE (Jun 2026)**

**Goal:** repos boot, CI is green, all conventions encoded.

| Area | Delivered |
|------|-----------|
| Docs | BRD, TECHNICAL_DESIGN, FRONTEND_DESIGN, TECH_STACK, ENVIRONMENT_SETUP, schema.sql, ROADMAP (this file) |
| Backend (`complaints/`) | Spring Boot 4.1, single-module Maven, Flyway V1.0/1.1/1.2 + dev seed, common (ApiResponse/PageResponse/ErrorCode/BusinessException/GlobalExceptionHandler/DateUtils), config (IST/Web/CORS/Security shell/Cache/OpenAPI/Bootstrap props), `ComplaintsApplication`, `AuthBootstrapRunner` skeleton, Dockerfile + docker-compose. Boots in 1.7 s in IST. |
| Tests | `ApiResponseTest`, `PackageBoundaryTest` (5 ArchUnit rules), `ComplaintsApplicationIT` (Testcontainers Postgres boot smoke). `./mvnw verify` green. |
| Copilot / agent | `.github/copilot-instructions.md` (incl. SOLID + design-pattern guidance + minimum-test policy), `AGENTS.md`, PR template, CONTRIBUTING.md, `.editorconfig`. |
| Frontend repo | **Not yet created** — kicked off at the start of Phase 1. |

**Done criteria:** ✅ Build green · ✅ App boots in dev · ✅ All conventions in `.github/copilot-instructions.md` · ✅ ArchUnit blocks rule violations.

---

## Phase 1 — Identity foundation + Master Data CRUD

**Goal:** an admin can log in, change their password, and create the subdivisions / DCs / categories that the rest of the system needs.

### Backend (`complaints/`)
- `auth` module — first vertical slice:
  - `UserAccount` entity (employee_id login, password_reset_required, created_by_user_id, scope rules)
  - `UserAccountRepository`
  - `JwtFactory` (per-purpose builders: staff-access, staff-refresh, consumer-verify)
  - `JwtAuthFilter` + `PasswordResetRequiredFilter` (replaces the `permitAll()` shim)
  - `AuthController` — `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/password/change`
  - `AuthBootstrapRunner` becomes real — actually seeds the first admin
- `masterdata` module:
  - `Subdivision`, `DistributionCenter`, `ComplaintCategory`, `SlaConfig` entities + repos + mappers
  - Admin CRUD endpoints (with soft-delete cascade per BRD §4.11)
  - `@Cacheable` on hot reads (categories / subdivisions / DCs)
- `consumer` module (read-only views):
  - `ConsumerMaster` entity + repo
  - `POST /admin/datasync/bulk-load` — CSV upload endpoint (multipart, JdbcTemplate batched upsert)

### Frontend (`complaints-frontend/` — created in this phase)
- Repo scaffold: pnpm + Turborepo + Vite + Expo + 4 shared packages + full CI gate from PR #1
- `packages/api` wired with orval against the backend's `/v3/api-docs`
- Web `staff/login` → `staff/force-change-password` → role-based redirect
- Admin screens: subdivisions / DCs / categories / SLA CRUD, EB CSV upload

### DevOps
- GitHub Actions on **both** repos: lint + test + build + size-limit + lighthouse (FE)
- `archunit.properties` flipped back to `failOnEmptyShould=true` once first module lands
- Dev seed data (`V1000.0`) validated end-to-end

### External dependencies
- None

### Done criteria
- Admin can boot, log in with employee_id, forced through password change, and CRUD master data via the web portal.
- Bootstrap runner correctly seeds first admin (verified by IT test).
- `./mvnw verify` + FE CI both green; first 5 ArchUnit rules now actually matching real classes.

**Rough size:** 1 medium chunk of work (1–2 weeks solo, 3–4 days with one BE + one FE dev).

---

## Phase 2 — Staff onboarding

**Goal:** admin can create engineers, engineers can create technicians.

### Backend
- `auth` module:
  - `StaffRegistrationService` (admin-creates-engineer/technician, engineer-creates-technician)
  - Endpoints under `/admin/staff/**` and `/engineer/staff/technicians`
  - Partial-unique-index enforcement (one admin/subdivision, one engineer/DC) — service + DB
  - Enable / disable endpoints

### Frontend
- Admin screens: list staff (filter by role + DC), create engineer, create technician, enable/disable
- Engineer screens: list own DC's technicians, create technician (FE doesn't ask for DC — derived from token)

### DevOps
- **Test env (Phase 2 of `ENVIRONMENT_SETUP.md`):**
  - GCP project + billing
  - `e2-small` VM with Postgres + systemd
  - `gs://complaints-images-test` + `gs://complaints-web-test` (website bucket per `FRONTEND_DESIGN.md §8.1`)
  - First test deploy of backend + web

### External
- GCP project + billing account

### Done criteria
- An admin creates an engineer, who creates a technician — each one forced through `password/change` on first login.
- Test env reachable at `https://storage.googleapis.com/complaints-web-test/index.html` + backend at `http://<VM-IP>:8080`.

**Rough size:** 1 small-to-medium chunk (3–5 days).

---

## Phase 3 — Consumer entry + complaint submission

**Goal:** a consumer can verify their mobile via OTP and submit a complaint with images. This is the "first user-facing feature".

### Backend
- `auth` module (consumer side):
  - `OtpService` (BCrypt-hashed OTP in `otp` table, `CONSUMER_VERIFY` purpose, 30 s cooldown, 5/hour/mobile via Bucket4j)
  - `ConsumerVerificationFilter` (gates `/consumer/**` with the 5-min token)
  - `/auth/consumer/otp/send` + `/auth/consumer/otp/verify`
  - `SmsService` strategy: console mock (dev) vs MSG91 sandbox (test) — chosen by `@ConditionalOnProperty`
- `complaint` module:
  - `Complaint`, `ComplaintImage`, `ComplaintHistory`, `ComplaintSequence`, `Feedback` entities
  - `TicketNumberService` — Postgres advisory lock on `hashtext('complaint_seq_' || yearMonth)` (per TD §4)
  - `ComplaintCreationService` — submit, derives DC from consumer_master
  - `ComplaintImageService` — multipart upload, client-side limits validated, written via `StorageService`
- `storage` module:
  - `StorageService` interface + `LocalStorageService` (dev) + `GcsStorageService` (test/prod, gated by `@Profile`)

### Frontend
- Consumer landing → OTP modal → submit-complaint form (web PWA)
- Image picker with client-side compression (browser-image-compression)
- Ticket-number confirmation screen (with copy + share)
- Draft persistence in `sessionStorage` (OTP-timeout safe)

### DevOps
- MSG91 sandbox account + key in test
- GCS bucket structure verified (`{complaintId}/COMPLAINT/{uuid}.jpg`)
- First end-to-end smoke from the test web bucket → test API VM → test DB → test image bucket

### External
- **MSG91 sandbox account** (free)

### Done criteria
- Consumer on the test web app can: enter Consumer ID + mobile → OTP delivered to MSG91 sandbox dashboard → verify → submit complaint with image → receive ticket number `MH<YYYY><MM>...`.
- Ticket-number sequence verified under concurrent load (a quick load test).

**Rough size:** 1 medium chunk (1–2 weeks).

---

## Phase 4 — Triage, assignment, and technician resolution

**Goal:** full complaint lifecycle works — engineer assigns to technician, technician resolves and closes.

### Backend
- `complaint` module (continued):
  - `ComplaintStatusTransition` (encoded allow-table for the state machine)
  - `ComplaintAssignmentService` — engineer/admin assign + reassign (admin can cross DCs)
  - `ComplaintTriageService` — severity update, reject, mark-duplicate
  - `ComplaintResolutionService` — technician start / resolve / close
  - `ComplaintClosureService` — engineer/admin close-on-behalf
  - SLA breach reason required-when-breached enforcement
  - `SlaMonitorService` — `@Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")` flags breaches
  - Resolution image upload endpoints
- `complaint` Specification-based search (status × severity × DC × technician × dateRange × search)

### Frontend (web)
- Engineer/Admin complaints list (TanStack Table, server-side pagination + filters)
- Complaint detail view with timeline + image gallery
- Assign / reassign / severity / reject / duplicate / close-on-behalf modals

### Frontend (mobile — `apps/mobile/`)
- Expo skeleton + React Navigation tabs
- Auth (re-uses staff login + force-password-change)
- Technician tabs: Assigned / In-Progress / History
- Per-complaint screen: status badge, SLA countdown, contact button (`tel:`), Open in Maps, image gallery
- Start → Resolve → Close action flow with breach-reason form

### DevOps
- Second test deploy (now FE + mobile both consuming the assignment API)
- Mobile internal testing track on Google Play (EAS Submit preview)
- Backend `Bucket4j` rate limit verified under fake load

### External
- Apple Developer Program ($99/yr) **or defer iOS to Phase 7**
- Google Play Developer account ($25 one-time)

### Done criteria
- End-to-end happy path: consumer submits → engineer assigns → technician starts → resolves → closes → consumer can see CLOSED status.
- Late closure path: SLA breach detected by scheduled job → reason required at close → audit captured.

**Rough size:** 1 large chunk (2–3 weeks). The biggest single phase by code volume.

---

## Phase 5 — Consumer view, cancel, feedback (PWA)

**Goal:** consumers can track, cancel, and give feedback — closes the consumer loop.

### Backend
- `complaint` module:
  - `GET /consumer/complaints` (lists all complaints for verified Consumer ID across mobiles)
  - `GET /consumer/complaints/{ticketNo}` (detail)
  - `POST /consumer/complaints/{ticketNo}/cancel` (status=SUBMITTED only, reason required)
  - `POST /consumer/complaints/{ticketNo}/feedback` (one-shot, rating + comment after CLOSED)

### Frontend (web PWA)
- Consumer "View my complaints" list + status timeline + cancel modal + feedback modal
- Service worker via `vite-plugin-pwa`: app shell offline, categories cached
- Install-prompt + Add-to-Home-Screen banner
- Background Sync for offline complaint submission (queued, posts on reconnect)

### DevOps
- PWA Lighthouse score ≥ 90 enforced in CI
- Consumer flow end-to-end smoke on Slow 4G throttling

### External
- None

### Done criteria
- Consumer can verify → submit → cancel (if SUBMITTED) → track to CLOSED → leave feedback, all from the installable PWA, with offline-submit working.

**Rough size:** 1 small-to-medium chunk (1 week).

---

## Phase 6 — Notifications, events, audit

**Goal:** the system *tells* staff what's happening, and we have an audit trail for compliance.

### Backend
- `notification` module:
  - `Notification`, `DeviceToken` entities + repos
  - `NotificationController` + `DeviceTokenController` + `NotificationPreferenceController`
  - `FcmService` strategy: mock (dev) vs FCM (test/prod) — gated by `@ConditionalOnProperty`
- Domain events (Spring `ApplicationEventPublisher` in dev/test, Pub/Sub in prod when adopted):
  - `ComplaintSubmittedEvent`, `ComplaintAssignedEvent`, `ComplaintReassignedEvent`, `ComplaintStatusChangedEvent`, `SlaBreachedEvent`
- `ComplaintEventListener` (in `notification` module) — persists `notification` row + dispatches FCM push (only to staff)
- `audit` module:
  - `AuditLog` entity + repo
  - `AuditListener` (also subscribes to domain events) — writes `audit_log` rows for every state change
  - `AuditLogArchiver` — nightly `@Scheduled` job (24-month online retention → GCS `.jsonl.gz` per TD §11)

### Frontend
- Web: notification bell + unread badge + dropdown + preferences page
- Mobile: device-token registration on login, push receiver, in-app notification list

### DevOps
- Firebase project + FCM service account in test
- Verified push delivery to Android emulator / device

### External
- **Firebase project** (free)

### Done criteria
- Engineer sees a push + in-app notification when a complaint is assigned to their DC.
- Technician sees a push when assigned.
- SLA breach triggers an engineer push.
- Every status change writes an `audit_log` row.
- Nightly archiver runs cleanly on a small DB.

**Rough size:** 1 medium chunk (1–2 weeks).

---

## Phase 7 — Hardening + production prep

**Goal:** the system is ready for real users on real infrastructure.

### Backend
- **Security hardening:**
  - JWT secret rotation strategy (env-driven, kid in header)
  - HTTPS-only enforced in prod profile
  - `BFF` web endpoint (`/auth/login/web` + `/auth/refresh/web`) that sets refresh token as httpOnly cookie (D8)
  - Optimistic-lock `version` column on `complaint` (D9)
- **Performance:**
  - Cache hit/miss metrics exposed (`/actuator/prometheus`) — ✅ Stage 21.2.5
  - HikariCP tuning per the prod load expectations (20/pod)
  - DB indexes verified by `EXPLAIN` on the hot queries — ✅ Stage 21.2.8 (`docs/DB_INDEX_AUDIT.md`, V1.6 adds `ix_complaint_category_status` + partial `ix_device_token_active_updated`)
- **Operational:**
  - Bucket4j rate-limit metrics
  - Structured logs (JSON) in prod
  - `/actuator/info` includes git SHA (correlates with FE Sentry releases)

### Frontend
- **i18n:** hi + mr catalogues (translator handoff via JSON in repo)
- **Sentry:** errors + Web Vitals + RN crashes; release identifier = git SHA
- **Mobile offline mode:** `persistQueryClient` AsyncStorage adapter; mutation queue
- **Full E2E coverage:** Playwright 4 critical journeys + Maestro 1 technician journey
- **Bundle budgets enforced** (size-limit + lighthouse-ci hard gates)
- **a11y audit:** WCAG 2.1 AA across all key flows via `@axe-core/playwright` blocking

### DevOps
- **GKE Autopilot** cluster in `asia-south1`
- **Cloud SQL HA** (PostgreSQL 16, regional)
- **Cloud Storage** prod buckets (images + web releases)
- **Cloud CDN** in front of `complaints-web-prod/`
- **GitHub-managed SSL** + custom domain
- **GCP Secret Manager** for JWT secret, DB password, MSG91 key, FCM service account
- **GitHub Actions** prod deploy workflow:
  - Backend → Docker image → Artifact Registry → `kubectl apply` (with manual approval)
  - Web → `gsutil rsync` into versioned path + CDN flip (sub-minute rollback)
  - Mobile → EAS Submit with 10% → 50% → 100% staged rollout
- **Cloud Monitoring** alerts: error rate > 1%, p95 latency > 2s, SLA breach count, CDN egress > $50/mo, Sentry quota > 80%, EAS build count > 25/30

### External
- **MSG91 prod template ID** (DLT-registered)
- **Custom domain** (e.g. `complaints.maharashtra.gov.in` — TBD)
- **Apple Developer Program** ($99/yr) — for iOS prod
- Cloud SQL & GKE Autopilot SKUs activated in the prod project

### Done criteria
- Smoke test green post-deploy.
- Load test: sustained ~12 RPS (10× v1 burst) holds p95 < 2 s.
- All Lighthouse/a11y/CSP/bundle CI gates green.
- Rollback drill: web rollback < 1 min, backend rollback via `kubectl rollout undo` < 5 min, mobile rollback halts within 1 staged-rollout band.

**Rough size:** 1 large chunk (2–3 weeks), much of it serial DevOps work.

---

## Phase 8 — Pilot launch + iteration

**Goal:** real users on real infra in a limited scope; tighten based on feedback before rolling wider.

- Pilot with **one Subdivision** (e.g. SUB-NSK-001 with 2 DCs and ~10 technicians).
- Daily standup with on-call rotation for 2 weeks.
- Triage feedback into v1.x patches and v2 backlog.
- After 30 days of stability, roll out to remaining subdivisions in waves.

### v2 backlog (out of scope for v1)
- **SYSADMIN role above subdivision-scoped Admins.** v1 has one Admin per Subdivision, permanently bound to that subdivision (BRD §4.2 line 62, Hard Rule 8). Two real gaps surface from this in v1:
  1. There is no role that can manage subdivisions themselves — yet `SubdivisionAdminController` currently lets any Admin create/edit/deactivate any Subdivision (the existing Stage 2 carry-over). v1 hardening (Phase 7) hides that surface from the Admin UI; v2 adds it back, gated by SYSADMIN.
  2. Cross-subdivision oversight (group reports, reassignment across subdivisions, deactivating an Admin to onboard their successor without a DBA shell) has no path today.

  **v2 shape:**
  - New `UserRole.SYSADMIN`. No subdivision scope; `subdivision_id` nullable for SYSADMIN only.
  - SYSADMIN can: CRUD all subdivisions, CRUD all Admins (cross-subdivision), read across all DCs and complaints. Cannot directly resolve complaints (operational work stays in the per-subdivision chain).
  - Admin role unchanged from v1: scope = their own Subdivision, manages all DCs / Engineers / Technicians under it.
  - DB: relax `chk_staff_scope` to permit `role=SYSADMIN AND subdivision_id IS NULL`; new partial unique `ux_one_active_sysadmin_global` (or drop the partial-unique entirely and allow many SYSADMINs — TBD with ops).
  - Frontend: the existing FE Admin UX is unaffected; new screens (subdivision CRUD, cross-subdivision staff list, "act-as" view of an Admin's scope) ship behind a SYSADMIN role gate.
  - Migration: existing bootstrap admin → SYSADMIN by default? Or seed a new `SYSADMIN001` and demote `ADMIN001` to a real subdivision-bound admin? Decide before v2 cutover.
- Consumer complaint re-open (currently must raise new complaint)
- Hierarchy levels above Subdivision (Division / Circle / Zone)
- Auto-assignment of complaints by workload / availability
- Reporting & analytics dashboards
- Multi-level escalation (Engineer → Admin → Division)
- Native consumer mobile app (currently PWA)
- Real-time EB system integration (currently bulk dump + nightly sync)
- Geo-location auto-detect on complaint form
- SMS status updates to consumers (currently consumers re-verify via OTP)
- WhatsApp Business integration
- Self-service staff forgot-password
- Optimistic-lock `version` column elevated to a richer conflict-resolution UI
- Cloud SQL read replicas for reporting
- Distributed tracing (OpenTelemetry → Cloud Trace)
- Move OTP / refresh-token / cache to Memorystore Redis (once > 1 pod)

---

## Cross-cutting concerns (running through every phase)

| Concern | How it shows up |
|---------|-----------------|
| **Tests** | Minimum-test policy from TD §14.2 applied per slice — 1 happy + 1 unhappy per service/controller. *Not* deferred to a "test phase". |
| **ArchUnit rules** | Tighten the rules as each module lands. `archRule.failOnEmptyShould=true` since Phase 1 Stage 2. Shape rules (records / mappers / repositories / no field injection) added in Stage 21.2.6. |
| **Docs** | Update `TECHNICAL_DESIGN.md` / `BRD.md` *in the same PR* as the code change. CONTRIBUTING.md "when you change X you must Y" enforces this. |
| **OpenAPI** | Springdoc annotations on every new controller. FE regenerates `@complaints/api` automatically. |
| **Secrets** | Always env-var or Secret Manager — never committed. gitleaks blocks accidents. |
| **IST timezone** | Every new date helper uses `DateUtils.IST`. Reviewers reject anything else. |
| **Observability** | New endpoints → log structured event + Sentry breadcrumb. No silent failures. |

---

## Quick visual

```
Phase 0 ──── Scaffolds & guardrails ──── ✅ DONE
Phase 1 ──── Identity + Master Data ─────────────────────► dev complete
Phase 2 ──── Staff onboarding ──────────────────────────────► test env up
Phase 3 ──── Consumer OTP + submit ───────────────────────────► first user-facing
Phase 4 ──── Triage / assignment / resolution ─────────────────► full lifecycle
Phase 5 ──── Consumer view / cancel / feedback (PWA) ────────────► consumer loop closed
Phase 6 ──── Notifications + events + audit ───────────────────────► reactive system
Phase 7 ──── Hardening + prod prep ───────────────────────────────────► production-ready
Phase 8 ──── Pilot launch + iterate ─────────────────────────────────────► live
```

Each phase ends with a deployable + demoable increment. Phases overlap where dependencies allow (FE starts a phase as soon as BE OpenAPI for that phase exists).

---

## What I'll pick up next

By default: **Phase 1 → backend slice first** (`UserAccount` entity + JWT factory + `/auth/login` + `/auth/password/change` + real `AuthBootstrapRunner`). That gives the FE team the OpenAPI spec they need to start their Phase 1 work in parallel.

If you'd rather start with the FE repo scaffold first (so the FE pipeline is wired up and consuming the *empty* OpenAPI spec from today's scaffold), say so and I'll flip the order.

