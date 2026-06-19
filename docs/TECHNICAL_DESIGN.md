# Technical Design Document
## Complaint Resolution System

---

## 1. High-Level Architecture

```
                          ┌─────────────────────────────────────────┐
                          │            CLIENTS                       │
                          │  ┌──────────────┐  ┌─────────────────┐  │
                          │  │  Web Portal  │  │  React Native   │  │
                          │  │   (React)    │  │  Mobile App     │  │
                           │  │ Consumers /  │  │  (Engineers /   │  │
                           │  │   Admins     │  │   Technicians)  │  │
                          │  └──────┬───────┘  └────────┬────────┘  │
                          └─────────┼────────────────────┼──────────┘
                                    │ HTTPS / REST       │
                                    ▼                    ▼
            ┌─────────────────────────────────────────────────────────┐
            │              API GATEWAY (Ingress / Nginx)               │
            │              JWT validation · Rate limiting              │
            └──────────────────────────┬──────────────────────────────┘
                                       │
                                       ▼
        ┌────────────────────────────────────────────────────────────────┐
        │   COMPLAINT RESOLUTION SERVICE (Spring Boot 4.1, modular)      │
        │   Modules: auth · complaint · masterdata · notification ·      │
        │            storage · datasync · audit · common                 │
        └──┬──────────────────┬─┬────────────────────────────────────────┘
           │                  │ │
           │                  │ └────────────────────────────┐
           ▼                  ▼                              ▼
   ┌────────────────┐ ┌──────────────────┐        ┌───────────────────┐
   │  PostgreSQL    │ │  Caffeine        │        │  GCS / Local FS   │
   │  - consumer_   │ │  (in-JVM cache)  │        │  - Complaint      │
   │    master      │ │  - Hot reads:    │        │    images         │
   │  - user_account│ │    categories,   │        │  - Resolution     │
   │  - subdivision │ │    subdivisions, │        │    images         │
   │  - distribution│ │    DCs           │        └───────────────────┘
   │    _center     │ │  - Bucket4j      │
   │  - complaint   │ │    rate limit    │
   │  - otp         │ └──────────────────┘
   │  - refresh_    │
   │    token       │
   │  - audit_log   │
   └────────────────┘
           │
           │     Async events
           ▼
   ┌──────────────────────────────┐
   │  Pub/Sub (or Spring Events)  │
   │  - complaint.submitted       │
   │  - complaint.assigned        │
   │  - complaint.statusChanged   │
   │  - sla.breached              │
   └──────────┬───────────────────┘
              ▼
   ┌──────────────────────────────┐
   │  Notification Worker         │
   │  - In-app notification (DB)  │
   │  - FCM push notification     │
   │  - MSG91 SMS (for OTP only)  │
   └──────────────────────────────┘
```

### Architecture Style
- **Modular Monolith** for v1 — single Spring Boot app with clear module boundaries.
- Auth lives **inside** the same app as a self-contained module (no separate auth service); JWT keeps subsequent calls stateless.
- Why monolith? Faster delivery, lower ops cost, easier debugging. Modules can be split into microservices later if needed.

---

## 2. Module Breakdown

| Module | Responsibilities |
|--------|------------------|
| **auth** | Consumer OTP verification (no consumer registration, no consumer login), staff onboarding (admins seeded via DB / bootstrap runner; engineers created by admin; technicians created by admin or engineer), staff login with Employee ID + password, JWT issue/refresh/revoke, OTP send/verify, first-login password change, bootstrap admin runner |
| **complaint** | Create (consumer / guest), engineer triage (set severity + assign), technician workflow (start / resolve / close), cancel / reject / mark-duplicate, image uploads, SLA monitor |
| **master-data** | Subdivision, Distribution Center, Category, SLA configuration (admin only). Soft-delete via `active` flag. |
| **notification** | In-app notifications, FCM push dispatch, MSG91 SMS dispatch (OTP only in v1) |
| **storage** | Image upload / download / compression abstraction (local FS in dev, GCS in test/prod) |
| **audit** | Audit trail of all status changes, assignments, master-data edits, admin actions |
| **datasync** | Sync of `consumer_master` + `subdivision` + `distribution_center` from external EB system. Implementation TBD — **Spring Batch job** or **REST API pull** (decision in Phase 2). Initial one-time bulk dump on go-live, periodic incremental sync after. |
| **common** | Shared exceptions, DTOs, security filters, enums, utilities (incl. `TicketNumberGenerator`) |

---

## 3. Package Structure

```
com.example.complaints
├── ComplaintsApplication.java
├── config
│   ├── SecurityConfig.java
│   ├── JwtConfig.java
│   ├── OpenApiConfig.java
│   ├── PubSubConfig.java         // active in test/prod
│   ├── StorageConfig.java        // local vs GCS based on profile
│   ├── CaffeineCacheConfig.java
│   └── WebConfig.java            // CORS, etc.
│
├── common
│   ├── dto         (ApiResponse, PageResponse, ErrorResponse)
│   ├── exception   (BusinessException, NotFoundException, ForbiddenException, GlobalExceptionHandler)
│   ├── util        (ImageCompressor, OtpGenerator, TicketNumberGenerator, DateUtils, Sha256)
│   ├── mapper      (base interfaces for hand-written *Mapper classes)
│   └── enums       (Role, ComplaintStatus, Severity, ImageType, OtpPurpose, NotificationType)
│
├── auth
│   ├── controller    (AuthController, OtpController, StaffRegistrationController, PasswordController)
│   ├── service       (AuthService, OtpService, JwtService, StaffRegistrationService, PasswordService, ConsumerVerificationService)
│   ├── repository    (UserAccountRepository, OtpRepository, RefreshTokenRepository)
│   ├── model         (UserAccount, Otp, RefreshToken)
│   ├── dto           (LoginRequest, LoginResponse, OtpSendRequest, OtpVerifyRequest, ConsumerVerifyResponse, StaffRegisterRequest, ChangePasswordRequest, ...)
│   ├── mapper        (UserAccountMapper)                              // plain Java
│   ├── security      (JwtAuthFilter, JwtTokenProvider, CustomUserDetailsService, AuthenticatedUser, ConsumerVerificationFilter, PasswordResetRequiredFilter)
│   └── bootstrap     (AuthBootstrapRunner)                          // seeds first admin from env vars
│
├── consumer
│   ├── controller    (ConsumerMasterController)                     // admin read-only views
│   ├── service       (ConsumerMasterService)
│   ├── repository    (ConsumerMasterRepository)
│   ├── model         (ConsumerMaster)
│   ├── dto           (ConsumerMasterResponse)
│   └── mapper        (ConsumerMasterMapper)
│
├── complaint
│   ├── controller    (ComplaintController, ComplaintAdminController, ComplaintTechnicianController)
│   ├── service       (ComplaintService, ComplaintAssignmentService, ComplaintCancellationService,
│   │                 ComplaintResolutionService, ComplaintImageService, SlaMonitorService,
│   │                 TicketNumberService)
│   ├── repository    (ComplaintRepository, ComplaintHistoryRepository, ComplaintImageRepository,
│   │                 ComplaintSequenceRepository, FeedbackRepository)
│   ├── model         (Complaint, ComplaintHistory, ComplaintImage, ComplaintSequence, Feedback)
│   ├── dto           (ComplaintCreateRequest, GuestComplaintCreateRequest, ComplaintResponse,
│   │                 AssignRequest, RejectRequest, MarkDuplicateRequest, ResolveRequest,
│   │                 CancelRequest, FeedbackRequest, ...)
│   ├── mapper        (ComplaintMapper, ComplaintImageMapper)
│   └── event         (ComplaintSubmittedEvent, ComplaintAssignedEvent, ComplaintStatusChangedEvent,
│                     SlaBreachedEvent)
│
├── masterdata
│   ├── controller    (SubdivisionController, DistributionCenterController,
│   │                 CategoryController, SlaController)
│   ├── service       (SubdivisionService, DistributionCenterService, CategoryService, SlaService)
│   ├── repository    (SubdivisionRepository, DistributionCenterRepository,
│   │                 CategoryRepository, SlaConfigRepository)
│   ├── model         (Subdivision, DistributionCenter, ComplaintCategory, SlaConfig)
│   ├── dto           (SubdivisionRequest/Response, DistributionCenterRequest/Response,
│   │                 CategoryRequest/Response, SlaConfigRequest/Response)
│   └── mapper        (...)
│
├── notification
│   ├── controller    (NotificationController, DeviceTokenController, NotificationPreferenceController)
│   ├── service       (NotificationService, FcmService, SmsService)
│   ├── repository    (NotificationRepository, DeviceTokenRepository)
│   ├── model         (Notification, DeviceToken)
│   ├── dto           (NotificationResponse, DeviceTokenRequest)
│   └── listener      (ComplaintEventListener)                       // listens to events, sends notifs
│
├── storage
│   ├── StorageService.java       (interface)
│   ├── LocalStorageService.java  (@Profile("dev"))
│   └── GcsStorageService.java    (@Profile({"test","prod"}))
│
├── audit
│   ├── service       (AuditService)
│   ├── repository    (AuditLogRepository)
│   └── model         (AuditLog)
│
└── datasync                      // external EB system sync (approach TBD: Spring Batch or REST)
    ├── config        (BatchConfig.java                — only if Spring Batch chosen)
    ├── job           (ConsumerMasterSyncJob, SubdivisionSyncJob, DistributionCenterSyncJob)
    ├── client        (EbSystemClient                  — for REST approach)
    ├── service       (DataSyncService)
    └── scheduler     (DataSyncScheduler               — @Scheduled triggers)
```

> **Flyway-managed DB migrations** live under `src/main/resources/db/migration/` (see Section 4).
> **DTO mapping** is done with **hand-written `*Mapper` classes** in each module (no MapStruct).

---

## 4. Database Schema

> Full DDL in [`schema.sql`](./schema.sql).

### 4.0 Migration Management — Flyway

All schema changes are managed by **Flyway** (auto-runs on app startup). Migration files live under:

```
src/main/resources/db/migration/
├── V1.0__init_schema.sql            # all tables + indexes (content of schema.sql)
├── V1.1__seed_master_data.sql       # seed complaint_category, default SLA config
├── V1.2__seed_bootstrap_admin.sql   # placeholder; real insert done at runtime by AuthBootstrapRunner from env vars
├── V1.3__init_update.sql            # example follow-up additive change
├── V1.4__add_resolution_images.sql  # example future change
└── ...
```

**Dev-only seed data** lives in a separate folder loaded only under the `dev` profile:

```
src/main/resources/db/migration-dev/
└── V1000.0__seed_dev_data.sql       # fake subdivisions, DCs, ~10 consumer_master rows, sample admin
```

Wired via Spring config (only the `dev` profile sets `spring.flyway.locations` to include `classpath:db/migration-dev`). The `V1000.x` numbering keeps dev-only versions out of the production migration sequence forever.

**Naming convention:** `V<major>.<minor>__<snake_case_description>.sql`
- `<major>` = product/release version (`1` for v1, bump to `2` for v2 schema-breaking work).
- `<minor>` = strictly increasing integer within the major version.
- Example progression: `V1.0`, `V1.1`, `V1.2`, ... `V1.42` → then `V2.0` for v2 release.

**Rules:**
- Versioned migrations (`V<x.y>__description.sql`) are **immutable** once committed and applied to any env.
- Never edit a migration that has run in dev/test/prod — write a new `V<x.y+1>__` instead.
- Repeatable migrations (`R__*.sql`) only for views, functions, seed-style upserts.
- Flyway runs **before** Hibernate validates the schema (`spring.jpa.hibernate.ddl-auto=validate`).
- CI runs Flyway against a Testcontainers Postgres to validate every PR.

**Spring config:**
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate     # never let Hibernate auto-create in any env
```

### Core Tables — Summary

The authoritative DDL lives in [`schema.sql`](./schema.sql). Below is a one-line summary of each table for navigation; refer to the SQL for columns, indexes and constraints.

| Table | Purpose |
|-------|---------|
| `subdivision` | Subdivision Office (taluka). Parent of distribution centers. Admin scope. |
| `distribution_center` | Distribution Center under a subdivision. Engineer + technician scope. |
| `complaint_category` | Complaint categories (POWER_OUTAGE, LOW_VOLTAGE, etc.) with per-category default SLA. |
| `sla_config` | Per-category SLA overrides, admin-editable. |
| `consumer_master` | Read-only consumer data from external EB system. Source of truth for "does this consumer exist". |
| `user_account` | **Staff-only** (Admin / Engineer / Technician). Login identifier is `employee_id`. Consumers do **not** have rows here. CHECK constraints enforce: role=ADMIN requires `subdivision_id` only; role=ENGINEER/TECHNICIAN require both `subdivision_id` + `distribution_center_id`. `password_reset_required` forces a change-password call before any other access. Partial-unique indexes enforce **one active admin per subdivision** and **one active engineer per DC**. |
| `otp` | OTP records with BCrypt hash, expiry, attempts, purpose (`CONSUMER_VERIFY`, `STAFF_PASSWORD_RESET`). |
| `refresh_token` | Hashed (SHA-256) refresh JWTs **for staff sessions** with `revoked` flag — enables logout & per-device revocation without Redis. |
| `complaint_sequence` | Per-month counter (`year_month → next_value`) used to build the ticket number `MH<YYYY><MM><8-digit-seq>`. |
| `complaint` | Main complaint table. Anchored to `consumer_master_id`. **`contact_mobile` is mandatory** (the OTP-verified mobile used at submission — need not match the on-file mobile in `consumer_master`). Severity is NULL at submission; set by engineer (`LOW/MEDIUM/HIGH`). Status flow enforced via CHECK + service layer. `parent_complaint_id` set when marked DUPLICATE. **No RE-OPENED in v1.** |
| `complaint_history` | Audit trail of every status change with actor + note. |
| `complaint_image` | Images per complaint, with `image_type` discriminator (`COMPLAINT` from consumer / `RESOLUTION` from technician). Max 3 of each per complaint. |
| `feedback` | One feedback row per complaint (rating 1-5 + comment). One-shot, not editable. |
| `notification` | In-app notifications per **staff** user with read/unread flag. (Consumers do not receive notifications in v1.) |
| `device_token` | FCM device tokens per **staff** user (iOS / Android). |
| `audit_log` | Generic audit log (`target_type`, `target_id`, `details` JSONB) for admin actions, sync runs, status changes. |

**Ticket number format:** `MH<YYYY><MM><8-digit-seq>` — e.g. `MH2026060000123`. The 8-digit sequence is taken atomically from `complaint_sequence` and resets every month.

**Month boundary** is computed in **Asia/Kolkata (IST)**, not UTC — a complaint submitted at `2026-06-30 23:30 IST` (`2026-06-30 18:00 UTC`) gets `MH202606…`, not `MH202607…`.

**Concurrency:** the per-month counter is incremented under a Postgres **advisory lock** keyed by the month, *not* a row lock. The `TicketNumberService` flow is:

```sql
SELECT pg_advisory_xact_lock(hashtext('complaint_seq_' || :yearMonth));
INSERT INTO complaint_sequence(year_month, next_value) VALUES (:yearMonth, 1)
    ON CONFLICT (year_month) DO UPDATE SET next_value = complaint_sequence.next_value + 1
    RETURNING next_value;
```

Why advisory lock over `SELECT … FOR UPDATE`:
- Lock is released automatically at transaction commit/rollback (`_xact_` variant) — no risk of orphaned row locks.
- No physical row contention / dead-tuple churn on `complaint_sequence` under burst.
- Hash-keyed → each month gets its own lock; different months never block each other.

**Indexes:** see `schema.sql`. Notable composite indexes:
- `complaint(distribution_center_id, status)` — engineer dashboard.
- `complaint(sla_deadline) WHERE sla_breached=false AND status NOT IN (terminal)` — SLA monitor partial index.
- `notification(user_id, is_read, created_at DESC)` — user notification list.

**Partitioning (prod):** by `created_at` on `complaint` + `complaint_history` (monthly partitions) when volume crosses ~5M rows.

---

## 5. REST API Contracts

> **Base URL:** `/api/v1`
> All responses wrapped in `ApiResponse<T>`: `{ success, data, error, timestamp }`.
> All endpoints documented via Swagger at `/swagger-ui.html` (locked behind HTTP Basic in test/prod — see §6).
> Pagination defaults: `?page=0&size=20&sort=createdAt,desc`; max `size = 100`. Wrapped in `PageResponse<T>` (`content, page, size, totalElements, totalPages`).

### 5.1 Auth APIs (public)

| Method | Endpoint | Description | Body |
|--------|----------|-------------|------|
| POST | `/auth/consumer/otp/send` | Validate Consumer ID against `consumer_master` and send a 6-digit OTP to the supplied mobile (any mobile, not just the on-file one). | `consumerId, mobile` |
| POST | `/auth/consumer/otp/verify` | Verify OTP and issue a **5-minute consumer verification JWT** carrying `consumerId, consumerMasterId, mobile`. This token is required by all `/consumer/**` endpoints. **Non-refreshable** — re-run the send/verify pair when expired. | `consumerId, mobile, otp` |
| POST | `/auth/login` | **Staff login** (Admin / Engineer / Technician) with **Employee ID + Password**. Returns access + refresh JWT. If `password_reset_required = true`, the access token is flagged and only `/auth/password/change` is accepted until the password is changed. | `employeeId, password` |
| POST | `/auth/refresh` | Rotate refresh token (issues new access + new refresh; old refresh is revoked). | `refreshToken` |
| POST | `/auth/logout` | Revoke the supplied refresh token (sets `revoked=true`). | `refreshToken` |
| POST | `/auth/password/change` | Authenticated staff change their own password. Required as the **first call** after first login (when `password_reset_required = true`) and usable any time after. Clears the flag and revokes all other refresh tokens. | `currentPassword, newPassword` |

> **No consumer registration, no consumer login, no consumer password, no consumer forgot-password.** All consumer interactions are gated by the 5-minute consumer verification JWT obtained via the two endpoints at the top of the table.

> **Bootstrap admin:** the very first `ADMIN` user is created by an in-app `AuthBootstrapRunner` on startup using `BOOTSTRAP_ADMIN_EMPLOYEE_ID`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_SUBDIVISION_CODE` env vars. No-op when an admin already exists. The bootstrap admin is also created with `password_reset_required = true`, so the first login forces a password change.

> **Staff (Admin / Engineer / Technician) are never self-registered via public APIs.** Admins are seeded directly in the DB (bootstrap runner on first boot, or DBA SQL insert for subsequent admins). Engineers are created by an Admin. Technicians are created by an Admin or by an Engineer (within the engineer's DC). See §5.2.

### 5.2 Staff Management APIs

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| POST | `/admin/staff/engineers` | ADMIN | Admin creates an Engineer in any DC under the admin's subdivision. Fails with `409 ENGINEER_ALREADY_EXISTS_FOR_DC` if an active engineer is already assigned to the target DC. Body: `employeeId, fullName, email, mobile, password, subdivisionId, distributionCenterId` |
| POST | `/admin/staff/technicians` | ADMIN | Admin creates a Technician in any DC under the admin's subdivision. Body: `employeeId, fullName, email, mobile, password, subdivisionId, distributionCenterId` |
| POST | `/engineer/staff/technicians` | ENGINEER | Engineer creates a Technician **scoped to the engineer's own DC** (subdivision + DC are derived from the engineer's account, not accepted in the body). Body: `employeeId, fullName, email, mobile, password` |
| GET | `/admin/staff?role=&distributionCenterId=` | ADMIN | List staff in the admin's subdivision |
| GET | `/engineer/staff/technicians` | ENGINEER | List technicians in the engineer's DC |
| PATCH | `/admin/staff/{id}/enabled` | ADMIN | Enable / disable a staff account. Body: `enabled` |
| PATCH | `/engineer/staff/technicians/{id}/enabled` | ENGINEER | Enable / disable a technician in the engineer's DC. Body: `enabled` |

> **Constraints enforced by both the service layer and partial-unique indexes:**
> - Exactly **one active Admin per Subdivision**.
> - Exactly **one active Engineer per Distribution Center**.
> - Many Technicians per Distribution Center.
> - `password_reset_required = true` is set on every staff account created via these endpoints (and on the bootstrap admin). The new user is forced to change the password on first login.

> Additional admins (beyond the bootstrap admin) are added via SQL / DBA — there is **no admin self-registration API and no admin-creates-admin API in v1**.

### 5.3 Consumer Complaint APIs

> All endpoints in this section require a valid **consumer verification JWT** in the `Authorization: Bearer <token>` header (issued by `/auth/consumer/otp/verify`, 5-min TTL). The `consumerMasterId` and `contactMobile` carried in the token are used as the authoritative consumer + contact mobile — they are **not** accepted from the request body.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/consumer/complaints` | Submit a complaint for the verified consumer. Body: `categoryCode, description, location?`. Server fills `consumer_master_id` + `contact_mobile` from the verification token, derives `distribution_center_id` from `consumer_master`, leaves `severity` NULL. |
| GET | `/consumer/complaints` | List **all** complaints filed against the verified Consumer ID (regardless of which mobile was used at submission). Paginated, filterable by status. |
| GET | `/consumer/complaints/{ticketNo}` | Get details for one of the verified consumer's complaints (includes `complaintImages[]` + `resolutionImages[]`). |
| POST | `/consumer/complaints/{ticketNo}/cancel` | Cancel — only when status = `SUBMITTED`. No time window. Body: `reason` (required). |
| POST | `/consumer/complaints/{ticketNo}/feedback` | Submit `rating (1-5), comment` after CLOSED. One-shot — cannot be edited. |
| POST | `/consumer/complaints/{id}/images` | Upload complaint image (multipart). Max 3 images per complaint, 1 MB each, only while status = `SUBMITTED`. |

> **No re-open in v1.** If the consumer is unsatisfied, they raise a **new complaint**.

### 5.4 Engineer / Admin Complaint Management

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| GET | `/staff/complaints` | ENGINEER, ADMIN | Paginated list filtered by scope: Engineer sees only their DC; Admin sees their subdivision. Supports filters `status, severity, technicianId, distributionCenterId (admin only), dateRange, search`. |
| POST | `/staff/complaints/{id}/assign` | ENGINEER, ADMIN | Assign to a technician AND set severity in one call. Engineer: technician must be in engineer's DC. Admin: technician must be in a DC within admin's subdivision. Body: `technicianId, severity (LOW/MEDIUM/HIGH)` |
| POST | `/staff/complaints/{id}/reassign` | ENGINEER, ADMIN | Reassign to another technician. **Engineer** → only to another technician in the **same DC**. **Admin** → may reassign to a technician in **any DC under the admin's subdivision** (the complaint's `distribution_center_id` and `assigned_engineer_id` are updated accordingly). Body: `technicianId, reason?` |
| POST | `/staff/complaints/{id}/severity` | ENGINEER, ADMIN | Update severity later (if needed). Body: `severity` |
| POST | `/staff/complaints/{id}/reject` | ENGINEER, ADMIN | Reject with reason. Body: `reason` (required) |
| POST | `/staff/complaints/{id}/mark-duplicate` | ENGINEER, ADMIN | Body: `parentTicketNo, reason?` |
| POST | `/staff/complaints/{id}/close` | ENGINEER, ADMIN | Close the complaint on behalf of the technician (e.g., technician unavailable, or breach happened before assignment). Allowed from `IN_PROGRESS` or `RESOLVED`; also from `SUBMITTED`/`ASSIGNED` *only* when `sla_breached = true` and the engineer/admin explicitly closes-with-reason. Body: `resolutionNotes (required if not already set), slaBreachReason (required if breached and not already given)`. |

### 5.5 Technician APIs

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| GET | `/technician/complaints` | TECHNICIAN | List complaints **assigned to me** (paginated, filterable by status) |
| POST | `/technician/complaints/{id}/start` | TECHNICIAN | Move ASSIGNED → IN_PROGRESS |
| POST | `/technician/complaints/{id}/resolve` | TECHNICIAN | Body: `resolutionNotes, slaBreachReason?` (required if `now() > sla_deadline`) |
| POST | `/technician/complaints/{id}/resolution-images` | TECHNICIAN | Upload resolution proof image (multipart). Max 3 per complaint, 1 MB each. Allowed in IN_PROGRESS or RESOLVED. |
| DELETE | `/technician/complaints/{id}/resolution-images/{imageId}` | TECHNICIAN | Remove a resolution image (only before CLOSED) |
| POST | `/technician/complaints/{id}/close` | TECHNICIAN | Final close. Body: `slaBreachReason?` (required if breached and not already given) |

### 5.6 Master Data APIs

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| GET/POST/PUT/DELETE | `/admin/subdivisions` | ADMIN | CRUD on subdivisions. DELETE = soft delete (`active=false`). **Cascade:** all DCs under this subdivision are soft-deleted, and all staff (admin/engineer/technician) in scope are auto-disabled (`enabled=false`). |
| GET/POST/PUT/DELETE | `/admin/distribution-centers` | ADMIN | CRUD on DCs. Soft delete. Body for POST/PUT includes `subdivisionId`. **Cascade:** all engineers + technicians whose `distribution_center_id` matches are auto-disabled (`enabled=false`). Open complaints in the DC are left untouched — they continue to point at the (now inactive) DC for audit/history; reassign them first if continued operation is needed. |
| GET/POST/PUT/DELETE | `/admin/categories` | ADMIN | CRUD on complaint categories. Soft delete. |
| GET/PUT | `/admin/sla` | ADMIN | View/Update SLA per category |
| GET (public) | `/categories` | — | List active categories for complaint form |
| GET (public) | `/subdivisions` | — | List active subdivisions (for staff registration form) |
| GET (public) | `/distribution-centers?subdivisionId=` | — | List active DCs under a subdivision |

### 5.7 Notification APIs (staff only)

> All endpoints below require a staff JWT (Admin / Engineer / Technician). Consumers do not have an account and do not receive in-app notifications or push in v1.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/notifications` | List my notifications (paginated, unread-first) |
| POST | `/notifications/{id}/read` | Mark as read |
| POST | `/notifications/read-all` | Mark all read |
| PUT | `/me/notification-prefs` | Opt-in / opt-out of push (single boolean: `pushEnabled`; in-app notifications are always recorded) |
| POST | `/me/device-tokens` | Register FCM device token. Body: `token, platform (IOS/ANDROID)` |
| DELETE | `/me/device-tokens/{token}` | Unregister |

---

## 6. Security Design

### Authentication
- **Spring Security** + custom **JwtAuthFilter** for staff JWTs and **ConsumerVerificationFilter** for consumer verification JWTs (only accepted on `/consumer/**`).
- JWT signed with **HS256** (dev/test) or **RS256** (prod, keys in GCP Secret Manager).
- **Two distinct JWT kinds:**
  - **Staff access token** — 30 min, `sub = employeeId`, `userId`, `role`, `subdivisionId`, `distributionCenterId`, `passwordResetRequired` (bool).
  - **Staff refresh token** — 7 days, rotated on every `/auth/refresh` (old token revoked, new pair issued); stored hashed in `refresh_token`.
  - **Consumer verification token** — 5 min, `sub = consumerId`, `consumerMasterId`, `mobile`, **non-refreshable**, not stored server-side. Re-issue requires a fresh OTP cycle.
- BCrypt password hashing (strength 12) for staff passwords only. Consumers have no password.

### Authorization (RBAC)
- Role enum (staff only): `TECHNICIAN`, `ENGINEER`, `ADMIN`. There is **no `CONSUMER` role** because consumers are not authenticated users — they are verified per-request via the consumer verification JWT.
- Method-level: `@PreAuthorize("hasRole('ADMIN')")`.
- Consumer endpoints under `/consumer/**` are guarded by `ConsumerVerificationFilter` (validates the verification JWT and exposes `consumerMasterId` + `contactMobile` to the controller). The `JwtAuthFilter` is **not** applied to `/consumer/**`.
- Ownership checks at service layer (e.g., a consumer can only view complaints whose `consumer_master_id` matches the one in their verification token).
- **Scope filters** applied automatically in repository queries based on the caller's role:
  - `ENGINEER` → `WHERE distribution_center_id = :myDcId`
  - `ADMIN` → `WHERE distribution_center_id IN (SELECT id FROM distribution_center WHERE subdivision_id = :mySubdivisionId)`
  - `TECHNICIAN` → `WHERE assigned_technician_id = :myUserId`

### First-Login Password Change
- Every staff account (including the bootstrap admin and every staff created via §5.2 APIs) is persisted with `password_reset_required = true`.
- On login, the access token carries this flag.
- A `PasswordResetRequiredFilter` (chained after `JwtAuthFilter`) inspects the flag: when `true`, only `POST /auth/password/change` and `POST /auth/logout` are allowed; every other request returns `403 PASSWORD_RESET_REQUIRED`.
- A successful `/auth/password/change` call clears the flag, BCrypt-hashes the new password, and **revokes all other refresh tokens** for that user (defence against the creator who knows the initial password).

### Bootstrap Admin
On startup `AuthBootstrapRunner` (a `CommandLineRunner`) checks whether any `user_account` with role=ADMIN exists.
- If **no admin** AND env vars `BOOTSTRAP_ADMIN_EMPLOYEE_ID` + `BOOTSTRAP_ADMIN_PASSWORD` + `BOOTSTRAP_ADMIN_SUBDIVISION_CODE` are all present → resolve subdivision by code, BCrypt-hash the password, insert the admin row (`enabled=true`, `password_reset_required=true`).
- If admin already exists → no-op.
- If env vars are missing on a fresh install → log WARN and continue (operator can create the admin via SQL or restart with env vars set).
Keeps the bootstrap password **out of source control** and out of Flyway SQL files.

### Consumer OTP / Verification Flow
1. `POST /auth/consumer/otp/send` → look up `consumerId` in `consumer_master`; if missing/inactive → `404 CONSUMER_NOT_FOUND`. Otherwise generate a 6-digit OTP, BCrypt-hash it, persist in `otp` with `purpose = CONSUMER_VERIFY`, `expires_at = now() + 5 min`, and send via SMS provider.
2. `POST /auth/consumer/otp/verify` → look up the latest non-consumed OTP for `(mobile, CONSUMER_VERIFY)`, compare hash, increment `attempts` (max 5 → invalidate). On success: mark `consumed = true` and issue the 5-minute **consumer verification JWT** carrying `consumerId, consumerMasterId, mobile`.
3. The token is **non-refreshable** and **not persisted server-side**. When it expires, the consumer starts over from step 1.
4. **Cleanup:** scheduled job (`@Scheduled(cron = "0 0 * * * *")`) deletes OTPs older than 24h.

### Staff Password-Reset Flow (post-v1 placeholder)
- v1 has **no public forgot-password** API for staff. A staff member who forgets their password asks an Admin (or, for an Admin, the DBA) to set a new initial password — which automatically sets `password_reset_required = true` again, forcing a fresh change at next login. A self-service email-based reset is on the v2 roadmap.

### Other Security Measures
- HTTPS everywhere in test/prod (Let's Encrypt or Google-managed SSL).
- CORS configured per env.
- **Rate limiting via Bucket4j** (in-memory, per pod):
  - **OTP send:** max **5 per mobile per hour**, with a **30-second cooldown** between consecutive sends to the same mobile (regardless of purpose).
  - **OTP verify:** max **5 attempts per OTP** (enforced via `otp.attempts`); on overflow the OTP is invalidated.
  - **Staff login:** max **10 attempts per IP per minute**.
  - *(Move to Redis-backed when scaling beyond 1 pod.)*
- Input validation (`@Valid` + Bean Validation).
- Global exception handler (no stack traces leaked).
- File upload validation (extension + magic bytes + size).
- Audit log for all admin actions and status changes.
- **Swagger / OpenAPI lockdown:**
  - `dev` profile → Swagger UI (`/swagger-ui.html`) and OpenAPI JSON (`/v3/api-docs`) are **publicly accessible** for fast iteration.
  - `test` and `prod` profiles → both paths require **HTTP Basic auth** (`SWAGGER_BASIC_USER` / `SWAGGER_BASIC_PASSWORD` env vars), enforced by a dedicated `SwaggerSecurityConfig` security chain that runs before `JwtAuthFilter`. The actual API endpoints still use JWT as usual; only the docs UI is gated.
  - To disable Swagger entirely in prod set `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`.

---

## 7. Notification Flow (Event-Driven)

```
ComplaintService.assignToTechnician()
        │
        ├── persists complaint update
        └── publishes ComplaintAssignedEvent
                        │
                        ▼
        ┌───────────────────────────────────┐
        │ Dev:  ApplicationEventPublisher   │  (in-memory, synchronous-ish)
        │ Prod: GCP Pub/Sub                 │  (async, durable)
        └───────────────┬───────────────────┘
                        ▼
              ComplaintEventListener
                        │
       ┌────────────────┼─────────────────┐
       ▼                ▼                 ▼
   Persist           FCM push to       FCM push to
   in `notification` consumer's        technician's
   table             devices           devices
```

### SLA Monitor
- Scheduled job (`@Scheduled(cron = "0 */15 * * * *")`) runs every 15 minutes.
- Finds complaints where `sla_deadline < NOW() AND sla_breached = false AND status NOT IN (RESOLVED, CLOSED, CANCELLED, REJECTED)`.
- Marks `sla_breached = true`, publishes `SlaBreachedEvent` → notifies engineer.

---

## 8. File Upload & Image Handling

Images come from two sources, both flow through the same `StorageService` abstraction:

| Source | Uploader | Endpoint | `image_type` | Allowed when status is |
|--------|----------|----------|--------------|------------------------|
| **Complaint image** | Consumer | `POST /complaints/{id}/images` | `COMPLAINT` | `SUBMITTED` (before assignment) |
| **Resolution image** | Technician | `POST /technician/complaints/{id}/resolution-images` | `RESOLUTION` | `IN_PROGRESS` or `RESOLVED` (before `CLOSED`) |

### Flow
1. Caller uploads image (multipart, max 1 MB).
2. App validates: count (≤3 per `image_type` per complaint), type (jpg/png), magic bytes, size.
3. Image is **compressed** (target 80% JPEG quality, max 1280px width) via `ImageCompressor` util.
4. `StorageService.upload(bytes, key)` → stored in:
   - **Dev:** `/app/uploads/{complaintId}/{imageType}/{uuid}.jpg`
   - **Test/Prod:** GCS bucket `complaints-images-{env}/{complaintId}/{imageType}/{uuid}.jpg`
5. Row inserted in `complaint_image` table with `image_type` and `uploaded_by_user_id`.
6. Reads via signed URL (24h expiry in prod).
7. `GET /complaints/{ticketNo}` response returns both arrays: `complaintImages[]` and `resolutionImages[]`.

---

## 9. Configuration per Profile

### `application.yml` (common)
```yaml
spring:
  application:
    name: complaints
  jackson:
    time-zone: Asia/Kolkata           # JSON timestamps render in IST (+05:30)
  jpa:
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: Asia/Kolkata     # tell Hibernate the JVM zone
  servlet:
    multipart:
      max-file-size: 1MB
      max-request-size: 5MB
  flyway:
    enabled: true
    locations: classpath:db/migration
jwt:
  access-token-ttl: 30m
  refresh-token-ttl: 7d
  consumer-verification-ttl: 5m
app:
  timezone: Asia/Kolkata              # used by services that need ZoneId explicitly
  complaint:
    default-sla-hours: 24
    max-images: 3
  pagination:
    default-size: 20
    max-size: 100
  otp:
    length: 6
    ttl: 5m
    max-per-mobile-per-hour: 5
    cooldown-seconds: 30
    max-attempts: 5
```

> JVM is also started with `-Duser.timezone=Asia/Kolkata` (set in the Dockerfile + systemd unit + dev `./mvnw` wrapper) — these YAML keys are belt-and-braces.

### `application-dev.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/complaints
    username: complaints
    password: complaints
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=10m
  flyway:
    locations: classpath:db/migration,classpath:db/migration-dev   # includes fake seed data
app:
  storage:
    type: local
    local-path: ./uploads
  sms:
    provider: console
  notification:
    push: mock
  cors:
    allowed-origins: "http://localhost:*"
```

### `application-test.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/complaints
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=10m
app:
  storage:
    type: gcs
    gcs-bucket: complaints-images-test
  sms:
    provider: msg91-sandbox
    msg91-key: ${MSG91_KEY}
  notification:
    push: fcm
    fcm-credentials: ${FCM_SA_JSON_PATH}
  swagger:
    basic-user: ${SWAGGER_BASIC_USER}
    basic-password: ${SWAGGER_BASIC_PASSWORD}    # Swagger UI requires HTTP Basic in test
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS}     # e.g. https://complaints-test.example.in
spring.cloud.gcp.project-id: ${GCP_PROJECT_ID}
```

### `application-prod.yml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql:///complaints?cloudSqlInstance=${CLOUDSQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=50000,expireAfterWrite=10m
    # When scaling beyond 1 pod, switch to:
    # type: redis
    # data.redis.host: ${MEMORYSTORE_HOST}
app:
  storage:
    type: gcs
    gcs-bucket: complaints-images-prod
  sms:
    provider: msg91
  notification:
    push: fcm
  swagger:
    basic-user: ${SWAGGER_BASIC_USER}
    basic-password: ${SWAGGER_BASIC_PASSWORD}    # Swagger UI requires HTTP Basic in prod (or set springdoc.*.enabled=false to disable entirely)
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS}     # exactly one origin, e.g. https://complaints.maharashtra.gov.in
```

> All secrets via env vars (dev) or GCP Secret Manager (prod).

---

## 10. Scalability & Performance

| Concern | Strategy |
|---------|----------|
| **Concurrent users** | Stateless app → horizontally scale pods (HPA on CPU + RPS). |
| **DB connections** | HikariCP, pool size = 20 per pod, Cloud SQL connector. |
| **Read load** | Add Cloud SQL read replicas for reporting queries in v2. |
| **Image uploads** | Direct-to-GCS signed URL uploads in v2 (skip app server for bytes). |
| **Notifications** | Async via Pub/Sub; FCM batch sends. |
| **OTP burst** | Bucket4j-based rate limit (5/hour/mobile, in-memory per pod). |
| **Search/filter** | Composite indexes on `(status, distribution_center_id, created_at)`. |
| **Cold start** | Min replicas = 2 in prod. |
| **DB growth** | Monthly partitioning of `complaint` and `complaint_history` after 5M rows. |

### Estimated load
- 10K complaints/day = ~0.12 RPS write-peak.
- Assume 10× read amplification (tracking, listing) = ~1.2 RPS read.
- Burst: 10× → ~12 RPS. Two `e2-medium` pods handle this comfortably.

---

## 11. Observability

| Aspect | Tool |
|--------|------|
| Logs | SLF4J + Logback → stdout → GCP Cloud Logging |
| Metrics | Micrometer → Prometheus (dev) / GCP Cloud Monitoring (prod) |
| Tracing (v2) | OpenTelemetry → Cloud Trace |
| Health | `/actuator/health`, `/actuator/info`, `/actuator/prometheus` |
| Alerts (prod) | Cloud Monitoring alerts on: error rate > 1%, p95 latency > 2s, SLA breach count |

### Audit Log Retention
- `audit_log` rows are kept **online (hot) for 24 months**, then archived to a Cloud Storage bucket (`gs://complaints-audit-archive-<env>/yyyy=YYYY/mm=MM/`) as compressed JSON Lines (`.jsonl.gz`) and **purged from the DB**.
- A nightly Spring `@Scheduled` job (`AuditLogArchiver`) handles archive + purge in 10K-row batches, recording its run in `audit_log` itself (`target_type='AUDIT_ARCHIVE'`).
- Archive retention in GCS: **7 years** (regulatory cushion for public-sector compliance), then lifecycle rule deletes objects.
- `complaint_history` follows the same 24-month / 7-year policy but partitioned monthly (see §4) so old partitions can simply be `DETACH`-ed + exported.

---

## 12. CI/CD

### GitHub Actions Workflows
1. **`ci.yml`** (on every PR): mvn test + Testcontainers + SonarCloud (optional).
2. **`deploy-test.yml`** (on merge to `develop`): build JAR → SCP to GCP VM → restart service.
3. **`deploy-prod.yml`** (on tag `v*`): build Docker image → push to GCP Artifact Registry → `kubectl apply` to GKE.

### Branching
- `main` → prod
- `develop` → test
- feature branches → PR to `develop`

---

## 13. Local Dev Workflow

```bash
# 1. start infra (just Postgres — Caffeine is in-JVM, no Redis needed)
docker-compose up -d postgres

# 2. run app (set BOOTSTRAP_ADMIN_* env vars on first run to seed the initial admin)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. open Swagger
open http://localhost:8080/swagger-ui.html
```

`docker-compose.yml` will provide:
- `postgres:16-alpine` on 5432
- optional: `prom/prometheus` + `grafana/grafana`

---

## 14. Testing Strategy

| Type | Tool | Scope |
|------|------|-------|
| Unit | JUnit 5 + Mockito | Services, validators, utils |
| Integration | Spring Boot Test + Testcontainers (Postgres) | Repositories + service flows |
| API Contract | MockMvc / RestAssured | Controllers + security |
| E2E | Selenium | Critical user journeys (web) |
| Load (v2) | k6 or Gatling | RPS targets |

Target coverage: **80%+** for services and controllers.

---

## 15. External Data Sync (Electricity Board System)

The system relies on the external EB system for **consumer master** and **subdivision / distribution-center** data.

### 15.1 Initial Bulk Dump (one-time, before go-live)
- EB team provides **CSV files** (UTF-8, comma-separated, header row) for the three datasets:
  - `consumers.csv` — columns: `consumer_id, name, mobile, email, address, dc_code, active`
  - `subdivisions.csv` — columns: `code, name, district, active`
  - `distribution_centers.csv` — columns: `code, subdivision_code, name, address, active`
- Loaded via an **admin-triggered loader endpoint** (`POST /admin/datasync/bulk-load` — multipart upload of the three CSVs, ADMIN only). Internally uses Spring's `JdbcTemplate` batched upserts on the natural keys (`consumer_id`, `subdivision.code`, `distribution_center.code`).
- Validated record counts + sample spot-checks before opening the system to consumers; per-run summary written to `audit_log` (`target_type='DATA_SYNC'`).

### 15.2 Periodic Incremental Sync (post go-live) — **Approach TBD**

We will pick **one** of the following based on what the EB system can offer:

| Option | When to choose | Pros | Cons |
|--------|----------------|------|------|
| **A. Spring Batch job** | EB exposes a daily CSV / DB snapshot / SFTP dump | Robust restart/retry, chunk-based, good for large dumps, well-suited to nightly windows | More boilerplate, needs scheduler |
| **B. REST API pull** | EB exposes paginated REST endpoints (e.g. `/changes?since=...`) | Near real-time, simpler code, easy to make idempotent | Couples uptime to EB API; need backoff/retry |
| **C. Webhook push from EB** | EB can call us on every change | Truly real-time, minimal polling cost | Requires EB team's effort; needs HMAC signature verification |

### 15.3 Package: `datasync`
- `EbSystemClient` — REST client (option B/C).
- `ConsumerMasterSyncJob` / `SubdivisionSyncJob` / `DistributionCenterSyncJob` — Spring Batch jobs (option A).
- `DataSyncService` — common upsert logic (idempotent: insert-or-update on `consumer_master.consumer_id`, `subdivision.code`, `distribution_center.code`).
- `DataSyncScheduler` — `@Scheduled(cron = "0 0 2 * * *")` triggers nightly sync (option A/B).
- `SyncAuditLog` — per-run summary: rows read / inserted / updated / failed.

### 15.4 Idempotency & Safety
- All upserts use natural keys (`consumer_master.consumer_id`, `subdivision.code`, `distribution_center.code`).
- Sync **never deletes** — uses a soft `active` flag instead, to avoid orphaning complaints.
- Per-run audit row written to `audit_log` (`target_type = 'DATA_SYNC'`).
- Failures alert via Cloud Monitoring (prod) / log warning (dev/test).

### 15.5 Decision Timeline
Final approach to be locked in **Phase 2** once the EB system's data-sharing capability is confirmed by their team. Until then, the `datasync` package will house only interfaces + a stub implementation.

---

## 16. Conventions & Defaults

These are the project-wide conventions every contributor follows. They are deliberately boring — the goal is zero bike-shedding during reviews.

### 16.1 Timezone — `Asia/Kolkata` (IST) everywhere

- **Persistence:** all timestamps stored as Postgres `TIMESTAMPTZ` (always UTC on disk).
- **Application zone:** the JVM is started with `-Duser.timezone=Asia/Kolkata`; Spring `@Configuration` also calls `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))` defensively.
- **All business calculations** that depend on a calendar/clock concept (SLA deadlines, the `<YYYY><MM>` portion of the ticket number, scheduler cron expressions, "today's complaints" filters) are computed in **IST**, never UTC.
- **JSON responses** serialize timestamps as ISO-8601 *with* the `+05:30` offset, e.g. `2026-06-19T14:30:00+05:30`.
- Cron schedules in `@Scheduled` use the explicit `zone = "Asia/Kolkata"` attribute.

### 16.2 DTO / Entity style

| Concern | Rule |
|---------|------|
| Request / response DTOs | **Java 21 `record`** types. Immutable, compact, validation annotations on the record component. |
| JPA entities | **Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`** (records don't work with JPA). |
| Mapping entity ↔ DTO | Hand-written `*Mapper` classes (see TECH_STACK §"Tools NOT chosen — MapStruct"). |
| Enums | Plain Java `enum` in `common.enums`; serialized as upper-case strings. |
| Validation | Jakarta Bean Validation (`@NotBlank`, `@Size`, `@Pattern`, `@Min`, custom validators in `common.validation`). |

### 16.3 Pagination

- Query params: `?page=0&size=20&sort=createdAt,desc` (Spring Data defaults, multi-`sort` allowed).
- Server caps `size` at **100**; requests above are clamped down (not rejected) and logged.
- Wrapped in `PageResponse<T>`: `{ content, page, size, totalElements, totalPages, sort }`.
- Defaults set globally via `@Configuration WebConfig.addPageableHandlerMethodArgumentResolver(...)`.

### 16.4 Error code catalogue

- Single `enum ErrorCode` in `common.exception.ErrorCode` — every business error maps to one entry, e.g.:
  - `CONSUMER_NOT_FOUND`, `OTP_INVALID`, `OTP_EXPIRED`, `OTP_RATE_LIMIT`
  - `ENGINEER_ALREADY_EXISTS_FOR_DC`, `ADMIN_ALREADY_EXISTS_FOR_SUBDIVISION`
  - `COMPLAINT_NOT_IN_SUBMITTED_STATE`, `COMPLAINT_NOT_OWNED_BY_CONSUMER`
  - `SLA_BREACH_REASON_REQUIRED`, `PASSWORD_RESET_REQUIRED`, `STAFF_ACCOUNT_DISABLED`
  - `IMAGE_LIMIT_EXCEEDED`, `IMAGE_TOO_LARGE`, `IMAGE_INVALID_TYPE`
  - `DC_INACTIVE`, `SUBDIVISION_INACTIVE`
- `BusinessException(ErrorCode, args…)` is the *only* exception thrown by services for business failures.
- `GlobalExceptionHandler` maps each `ErrorCode` to (HTTP status, code string, default message); the response is `ApiResponse.error = { code: "CONSUMER_NOT_FOUND", message: "...", details?: {...} }`.
- Adding a new error = one enum entry + one entry in `messages_en.properties` (and `_hi`, `_mr` once i18n lands).

### 16.5 API versioning

- Base URL **`/api/v1`** for everything in v1.
- **Additive** changes (new endpoint, new optional request field, new response field) stay on `/api/v1` — no version bump.
- **Breaking** changes (removed field, changed semantics, removed/renamed endpoint, changed enum value) → introduce `/api/v2` and run both in parallel for at least one release cycle before deprecating `/api/v1`.
- Deprecated endpoints return the standard response **plus** a `Deprecation: true` header and a `Sunset: <RFC-1123 date>` header.

### 16.6 CORS

- Origin allow-list comes from `app.cors.allowed-origins` (comma-separated env var), wired by profile:
  - **dev** → `http://localhost:*` (regex; allows any port).
  - **test** → the single test web-app URL (e.g. `https://complaints-test.<domain>`).
  - **prod** → the single prod web-app domain (e.g. `https://complaints.maharashtra.gov.in`).
- Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`.
- Allowed headers: `Authorization, Content-Type, X-Request-Id`.
- Credentials: **disabled** (we don't use cookies — JWT in `Authorization` header only).
- Preflight cache: 1 hour.
- Wired centrally via `WebConfig.addCorsMappings(...)` — no per-controller CORS annotations.

### 16.7 Build / module layout

- **Single-module Maven** (see `TECH_STACK.md → Build Layout`).
- ArchUnit tests under `src/test/java/com/example/complaints/architecture/` enforce cross-package import rules.

---

## 17. Open Items / V2 Roadmap

- **Consumer complaint re-open** (currently consumers must raise a new complaint).
- Hierarchy levels **above Subdivision** (Division / Circle / Zone).
- Auto-assignment of complaints based on DC + technician availability / workload.
- Reporting & analytics dashboards (complaints by area, avg resolution time, SLA compliance, engineer performance).
- Multi-level escalation (Engineer → Admin → Division-level …).
- Consumer mobile app (React Native).
- Real-time API integration with external EB system (currently bulk dump + nightly sync).
- Geo-location auto-detect on complaint form.
- SMS + Email notifications (beyond OTP) — in particular, **SMS status updates to consumers** (currently consumers must re-verify with OTP to check status; no push channel for them in v1).
- **Self-service staff forgot-password** (email-based reset link). v1 requires an admin/DBA to issue a new initial password.
- **Consumer mobile app account** (login + persistent session + push) — v1 keeps consumers anonymous / OTP-verified per use.
- WhatsApp Business API integration.
- Cloud SQL read replicas for reporting workloads.
- Distributed tracing (OpenTelemetry → Cloud Trace).
- Move OTP / refresh-token / cache to **GCP Memorystore Redis** when scaling beyond 1 pod.
