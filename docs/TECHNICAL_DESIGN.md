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
                          │  │   Admins     │  │  Field Tech)    │  │
                          │  └──────┬───────┘  └────────┬────────┘  │
                          └─────────┼────────────────────┼──────────┘
                                    │ HTTPS / REST       │
                                    ▼                    ▼
            ┌─────────────────────────────────────────────────────────┐
            │              API GATEWAY (Ingress / Nginx)               │
            │              JWT validation · Rate limiting              │
            └──────────────────────────┬──────────────────────────────┘
                                       │
                       ┌───────────────┴───────────────┐
                       ▼                               ▼
        ┌──────────────────────────┐    ┌──────────────────────────┐
        │  COMPLAINT SERVICE       │    │  AUTH SERVICE (in-proc)  │
        │  (Spring Boot 4.1)       │    │  - Register/Login        │
        │  - Complaint CRUD        │    │  - OTP                   │
        │  - Assignment            │    │  - JWT issue/refresh     │
        │  - Status updates        │    │  - Staff approval        │
        │  - Notifications         │    └──────────────────────────┘
        │  - File uploads          │
        │  - Reporting (v2)        │
        └──┬──────────────────┬─┬─┘
           │                  │ │
           │                  │ └────────────────────────────┐
           ▼                  ▼                              ▼
   ┌────────────────┐ ┌──────────────────┐        ┌───────────────────┐
   │  PostgreSQL    │ │  Caffeine        │        │  GCS / Local FS   │
   │  - Consumers   │ │  (in-JVM cache)  │        │  - Complaint      │
   │  - Complaints  │ │  - Hot reads:    │        │    images         │
   │  - Stations    │ │    categories,   │        └───────────────────┘
   │  - OTPs        │ │    substations   │
   │  - Refresh     │ │  - Bucket4j      │
   │    tokens      │ │    rate limit    │
   │  - Audit log   │ └──────────────────┘
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
- **Modular Monolith** for v1 — single Spring Boot app with clear module boundaries (auth, complaint, notification, master-data).
- Why? Faster delivery, lower ops cost, easier debugging. Can be split into microservices later if needed.

---

## 2. Module Breakdown

| Module | Responsibilities |
|--------|------------------|
| **auth** | Registration (consumer + staff), login, OTP, JWT, staff approval workflow |
| **consumer** | Consumer profile read/update; integration with external EB system |
| **complaint** | Create, assign, update status, cancel, reopen, close, attach images |
| **master-data** | Station, Substation, Category, SLA configuration (admin only) |
| **notification** | In-app notifications, FCM push dispatch, SMS dispatch (via MSG91) |
| **storage** | Image upload / download / compression abstraction (local FS in dev, GCS in test/prod) |
| **audit** | Audit trail of all status changes, assignments, and admin actions |
| **datasync** | Sync of consumer + station/substation data from external EB system. Implementation TBD — **Spring Batch job** or **REST API pull** (decision in Phase 2). Initial one-time bulk dump on go-live, periodic incremental sync after. |
| **common** | Shared exceptions, DTOs, security filters, utilities |

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
│   └── WebConfig.java            // CORS, etc.
│
├── common
│   ├── dto         (ApiResponse, PageResponse, ErrorResponse)
│   ├── exception   (BusinessException, NotFoundException, GlobalExceptionHandler)
│   ├── util        (ImageCompressor, OtpGenerator, DateUtils)
│   └── enums       (Role, ComplaintStatus, Severity, Category)
│
├── auth
│   ├── controller   (AuthController, OtpController, StaffRegistrationController)
│   ├── service      (AuthService, OtpService, JwtService, StaffApprovalService)
│   ├── repository   (UserRepository, OtpRepository, StaffRegistrationRequestRepository)
│   ├── model        (User, StaffRegistrationRequest)
│   ├── dto          (LoginRequest, LoginResponse, RegisterRequest, OtpVerifyRequest, ...)
│   └── security     (JwtAuthFilter, JwtTokenProvider, CustomUserDetailsService)
│
├── consumer
│   ├── controller   (ConsumerController)
│   ├── service      (ConsumerService)
│   ├── repository   (ConsumerRepository)
│   ├── model        (Consumer)
│   └── dto          (ConsumerResponse, ConsumerUpdateRequest)
│
├── complaint
│   ├── controller   (ComplaintController, ComplaintAdminController)
│   ├── service      (ComplaintService, ComplaintAssignmentService, SlaMonitorService)
│   ├── repository   (ComplaintRepository, ComplaintHistoryRepository, FeedbackRepository)
│   ├── model        (Complaint, ComplaintHistory, ComplaintImage, Feedback)
│   ├── dto          (ComplaintCreateRequest, ComplaintResponse, StatusUpdateRequest, ...)
│   └── event        (ComplaintSubmittedEvent, ComplaintAssignedEvent, SlaBreachedEvent)
│
├── masterdata
│   ├── controller   (StationController, SubstationController, CategoryController, SlaController)
│   ├── service
│   ├── repository
│   └── model        (Station, Substation, Category, SlaConfig)
│
├── notification
│   ├── controller   (NotificationController)
│   ├── service      (NotificationService, FcmService, SmsService)
│   ├── repository   (NotificationRepository, DeviceTokenRepository)
│   ├── model        (Notification, DeviceToken)
│   └── listener     (ComplaintEventListener) // listens to events, sends notifs
│
├── storage
│   ├── StorageService.java   (interface)
│   ├── LocalStorageService.java  (@Profile("dev"))
│   └── GcsStorageService.java    (@Profile({"test","prod"}))
│
└── audit
    ├── service      (AuditService)
    ├── repository   (AuditLogRepository)
    └── model        (AuditLog)

└── datasync         // external EB system sync (approach TBD: Spring Batch or REST)
    ├── config       (BatchConfig.java — only if Spring Batch chosen)
    ├── job          (ConsumerSyncJob, SubstationSyncJob)
    ├── client       (EbSystemClient — for REST approach)
    ├── service      (DataSyncService)
    └── scheduler    (DataSyncScheduler — @Scheduled triggers)
```

> **Flyway-managed DB migrations** live under `src/main/resources/db/migration/` (see Section 4).

---

## 4. Database Schema

> Full DDL in [`schema.sql`](./schema.sql).

### 4.0 Migration Management — Flyway

All schema changes are managed by **Flyway** (auto-runs on app startup). Migration files live under:

```
src/main/resources/db/migration/
├── V1.0__init_schema.sql           # all tables + indexes (content of schema.sql)
├── V1.1__seed_master_data.sql      # seed complaint_category, default SLA config
├── V1.2__seed_admin_user.sql       # bootstrap initial admin (env-driven password hash)
├── V1.3__init_update.sql           # example follow-up additive change
├── V1.4__add_resolution_images.sql # example future change
└── ...
```

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

### Core Tables

#### `consumer`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| consumer_id | VARCHAR(50) UNIQUE NOT NULL | From external EB system |
| name | VARCHAR(200) | |
| mobile | VARCHAR(15) NOT NULL | Indexed |
| email | VARCHAR(200) | |
| address | TEXT | |
| substation_id | BIGINT FK → substation.id | |
| created_at, updated_at | TIMESTAMPTZ | |

#### `user_account`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| username | VARCHAR(100) UNIQUE NOT NULL | Consumer ID or staff ID |
| password_hash | VARCHAR(255) NOT NULL | BCrypt |
| role | VARCHAR(30) NOT NULL | CONSUMER, ENGINEER, FIELD_TECHNICIAN, ADMIN |
| consumer_id | BIGINT FK → consumer.id NULL | only if role = CONSUMER |
| email | VARCHAR(200) | |
| mobile | VARCHAR(15) | |
| enabled | BOOLEAN DEFAULT TRUE | false until staff is approved |
| notifications_enabled | BOOLEAN DEFAULT TRUE | |
| created_at, updated_at | TIMESTAMPTZ | |

#### `staff_registration_request`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| email, mobile, full_name | | |
| requested_role | VARCHAR(30) | |
| status | VARCHAR(20) | PENDING, APPROVED, REJECTED |
| reviewed_by | BIGINT FK → user_account.id | |
| reviewed_at | TIMESTAMPTZ | |
| reason | TEXT | rejection reason |

#### `station`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| code | VARCHAR(50) UNIQUE |
| name | VARCHAR(200) |
| district | VARCHAR(100) |

#### `substation`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| station_id | BIGINT FK → station.id |
| code | VARCHAR(50) UNIQUE |
| name | VARCHAR(200) |
| address | TEXT |

#### `complaint_category`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| code | VARCHAR(50) UNIQUE  | POWER_OUTAGE, LOW_VOLTAGE, TRANSFORMER_FAULT, OTHER |
| name | VARCHAR(200) |
| sla_hours | INT | default 24, overridable |
| active | BOOLEAN |

#### `complaint`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| ticket_no | VARCHAR(30) UNIQUE | e.g. `CMP-2026-000123` |
| consumer_id | BIGINT FK → consumer.id | |
| created_by_user_id | BIGINT FK → user_account.id NULL | null if guest |
| is_guest | BOOLEAN | |
| guest_mobile | VARCHAR(15) NULL | for guest |
| category_id | BIGINT FK → complaint_category.id | |
| severity | VARCHAR(20) | LOW/MEDIUM/HIGH/CRITICAL |
| description | TEXT | |
| location | TEXT | |
| substation_id | BIGINT FK → substation.id | derived from consumer |
| status | VARCHAR(30) | SUBMITTED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED, REJECTED, DUPLICATE |
| assigned_engineer_id | BIGINT FK → user_account.id NULL | |
| assigned_technician_id | BIGINT FK → user_account.id NULL | |
| parent_complaint_id | BIGINT FK → complaint.id NULL | for DUPLICATE |
| sla_deadline | TIMESTAMPTZ | |
| sla_breached | BOOLEAN DEFAULT FALSE | |
| resolution_notes | TEXT NULL | |
| sla_breach_reason | TEXT NULL | required if closed after deadline |
| cancellation_reason | TEXT NULL | |
| rejection_reason | TEXT NULL | |
| created_at, updated_at, resolved_at, closed_at | TIMESTAMPTZ | |

**Indexes:** `(status)`, `(consumer_id)`, `(assigned_technician_id)`, `(assigned_engineer_id)`, `(substation_id, status)`, `(sla_deadline) WHERE sla_breached = false`.

**Partitioning (prod):** by `created_at` (monthly partitions) when volume crosses 5M rows.

#### `complaint_history`
Audit trail of every status change.
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| complaint_id | BIGINT FK |
| from_status, to_status | VARCHAR(30) |
| changed_by_user_id | BIGINT FK |
| note | TEXT |
| changed_at | TIMESTAMPTZ |

#### `complaint_image`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| complaint_id | BIGINT FK |
| image_type | VARCHAR(20) | `COMPLAINT` (uploaded by consumer at submission) or `RESOLUTION` (uploaded by field technician at resolution) |
| storage_key | TEXT | path in GCS or local FS |
| size_bytes | INT |
| content_type | VARCHAR(50) |
| uploaded_by_user_id | BIGINT FK NULL | technician id for RESOLUTION type |
| created_at | TIMESTAMPTZ |

> Max **3 images per type per complaint** (so a complaint can have up to 3 complaint images + 3 resolution images). Each max 1 MB, compressed before storage. Validated at app layer.

#### `feedback`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| complaint_id | BIGINT UNIQUE FK |
| rating | INT (1-5) |
| comment | TEXT |
| created_at | TIMESTAMPTZ |

#### `otp`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| mobile | VARCHAR(15) |
| otp_hash | VARCHAR(255) | hashed OTP |
| purpose | VARCHAR(30) | LOGIN, REGISTER, GUEST_COMPLAINT, PASSWORD_RESET |
| expires_at | TIMESTAMPTZ |
| consumed | BOOLEAN DEFAULT FALSE |
| attempts | INT DEFAULT 0 |
| created_at | TIMESTAMPTZ |

> Stored in Postgres for v1. (Redis-backed store deferred until multi-pod scale.)

#### `refresh_token`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| user_id | BIGINT FK → user_account.id |
| token_hash | VARCHAR(255) | SHA-256 of refresh JWT |
| expires_at | TIMESTAMPTZ |
| revoked | BOOLEAN DEFAULT FALSE |
| created_at, last_used_at | TIMESTAMPTZ |

> Enables logout & per-device revocation without a distributed cache.

#### `notification`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| user_id | BIGINT FK |
| type | VARCHAR(50) | COMPLAINT_ASSIGNED, STATUS_CHANGED, SLA_BREACH, ... |
| title | VARCHAR(200) |
| body | TEXT |
| complaint_id | BIGINT FK NULL |
| read | BOOLEAN DEFAULT FALSE |
| created_at | TIMESTAMPTZ |

#### `device_token`
For FCM push notifications.
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| user_id | BIGINT FK |
| token | TEXT UNIQUE |
| platform | VARCHAR(20) | IOS / ANDROID |
| created_at, last_used_at | TIMESTAMPTZ |

#### `sla_config`
Single-row config, admin-editable.
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| category_id | BIGINT FK UNIQUE |
| sla_hours | INT |
| updated_by | BIGINT FK → user_account.id |
| updated_at | TIMESTAMPTZ |

#### `audit_log`
| Column | Type |
|--------|------|
| id | BIGSERIAL PK |
| actor_user_id | BIGINT FK |
| action | VARCHAR(100) |
| target_type | VARCHAR(50) | e.g. COMPLAINT, USER, STATION |
| target_id | BIGINT |
| details | JSONB |
| created_at | TIMESTAMPTZ |

---

## 5. REST API Contracts

> **Base URL:** `/api/v1`
> All responses wrapped in `ApiResponse<T>`: `{ success, data, error, timestamp }`.
> All endpoints documented via Swagger at `/swagger-ui.html`.

### 5.1 Auth APIs (public)

| Method | Endpoint | Description | Body |
|--------|----------|-------------|------|
| POST | `/auth/consumer/register` | Consumer self-register | `consumerId, mobile, password` |
| POST | `/auth/login` | Login (consumer or staff) | `username, password` → returns access + refresh JWT |
| POST | `/auth/refresh` | Refresh access token | `refreshToken` |
| POST | `/auth/logout` | Invalidate refresh token | `refreshToken` |
| POST | `/auth/otp/send` | Send OTP | `mobile, purpose` |
| POST | `/auth/otp/verify` | Verify OTP | `mobile, otp, purpose` → returns short-lived verification token |
| POST | `/auth/password/forgot` | Initiate password reset | `consumerId, mobile` |
| POST | `/auth/password/reset` | Reset with OTP | `consumerId, otp, newPassword` |
| POST | `/auth/staff/register-request` | Staff registration request | `name, email, mobile, requestedRole` |

### 5.2 Staff Approval APIs (ADMIN)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/admin/staff/requests?status=PENDING` | List requests |
| POST | `/admin/staff/requests/{id}/approve` | Approve → creates user_account |
| POST | `/admin/staff/requests/{id}/reject` | Reject with reason |
| POST | `/admin/staff/register` | Admin creates staff directly |

### 5.3 Complaint APIs

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| POST | `/complaints` | CONSUMER (auth) | Submit complaint |
| POST | `/complaints/guest` | PUBLIC (after OTP verify) | Submit as guest |
| GET | `/complaints/{ticketNo}` | CONSUMER (own only) / staff | Get details |
| GET | `/complaints/my` | CONSUMER | List own complaints (paginated) |
| POST | `/complaints/{ticketNo}/cancel` | CONSUMER | Cancel (only if status=SUBMITTED), body: `reason` |
| POST | `/complaints/{ticketNo}/reopen` | CONSUMER | Reopen if not satisfied |
| POST | `/complaints/{ticketNo}/feedback` | CONSUMER | Submit `rating, comment` |
| POST | `/complaints/{id}/images` | CONSUMER (own) | Upload image (multipart) |

### 5.4 Engineer / Admin Complaint Management

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| GET | `/admin/complaints` | ENGINEER, ADMIN | List with filters (status, substation, technician, date range) |
| POST | `/admin/complaints/{id}/assign` | ENGINEER, ADMIN | Assign to technician, body: `technicianId` |
| POST | `/admin/complaints/{id}/reject` | ENGINEER, ADMIN | Reject with reason |
| POST | `/admin/complaints/{id}/mark-duplicate` | ENGINEER, ADMIN | Body: `parentComplaintId` |

### 5.5 Field Technician APIs

| Method | Endpoint | Role | Description |
|--------|----------|------|-------------|
| GET | `/technician/complaints` | FIELD_TECHNICIAN | List assigned to me |
| POST | `/technician/complaints/{id}/start` | FIELD_TECHNICIAN | Move to IN_PROGRESS |
| POST | `/technician/complaints/{id}/resolve` | FIELD_TECHNICIAN | Body: `resolutionNotes, slaBreachReason?` |
| POST | `/technician/complaints/{id}/resolution-images` | FIELD_TECHNICIAN | Upload resolution proof image (multipart), max 3 per complaint, 1 MB each |
| DELETE | `/technician/complaints/{id}/resolution-images/{imageId}` | FIELD_TECHNICIAN | Remove a resolution image (only before close) |
| POST | `/technician/complaints/{id}/close` | FIELD_TECHNICIAN | Final close |

### 5.6 Master Data APIs (ADMIN)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET/POST/PUT/DELETE | `/admin/stations` | CRUD on stations |
| GET/POST/PUT/DELETE | `/admin/substations` | CRUD on substations |
| GET/POST/PUT/DELETE | `/admin/categories` | CRUD on complaint categories |
| GET/PUT | `/admin/sla` | View/Update SLA config |
| GET (public) | `/categories` | List for complaint form |
| GET (public) | `/stations` / `/substations?stationId=` | List for dropdowns |

### 5.7 Notification APIs (auth)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/notifications` | List my notifications (paginated, unread-first) |
| POST | `/notifications/{id}/read` | Mark as read |
| POST | `/notifications/read-all` | Mark all read |
| PUT | `/me/notification-prefs` | Opt-in / opt-out |
| POST | `/me/device-tokens` | Register FCM device token |
| DELETE | `/me/device-tokens/{token}` | Unregister |

---

## 6. Security Design

### Authentication
- **Spring Security** + custom **JwtAuthFilter**.
- JWT signed with **HS256** (dev/test) or **RS256** (prod, keys in GCP Secret Manager).
- **Access token:** 30 min; **Refresh token:** 7 days.
- Refresh tokens stored in **`refresh_token` table** (with user binding + revoked flag) for revocation support. *(Redis-based store deferred to post-v1.)*
- BCrypt password hashing (strength 12).

### Authorization (RBAC)
- Role enum: `CONSUMER`, `FIELD_TECHNICIAN`, `ENGINEER`, `ADMIN`.
- Method-level: `@PreAuthorize("hasRole('ADMIN')")`.
- Ownership checks at service layer (e.g., consumer can only view own complaints).

### OTP Flow
1. `POST /auth/otp/send` → generate 6-digit OTP, BCrypt-hash it, persist in `otp` table with `expires_at = now() + 5 min`, send via SMS provider.
2. `POST /auth/otp/verify` → look up latest non-consumed OTP for `(mobile, purpose)`, compare hash, increment `attempts` (max 5 → invalidate).
3. On success: mark `consumed = true`, issue short-lived `verification token` (5 min, JWT) to use in the next API call (e.g., guest complaint submission).
4. **Cleanup:** a scheduled job (`@Scheduled(cron = "0 0 * * * *")`) deletes OTPs older than 24h.

### Other Security Measures
- HTTPS everywhere in test/prod (Let's Encrypt or Google-managed SSL).
- CORS configured per env.
- **Rate limiting via Bucket4j** (in-memory, per pod) — e.g., 5 OTP requests per mobile per hour, 10 logins per IP per minute. *(Move to Redis-backed when scaling beyond 1 pod.)*
- Input validation (`@Valid` + Bean Validation).
- Global exception handler (no stack traces leaked).
- File upload validation (extension + magic bytes + size).
- Audit log for all admin actions and status changes.

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
| **Resolution image** | Field Technician | `POST /technician/complaints/{id}/resolution-images` | `RESOLUTION` | `IN_PROGRESS` or `RESOLVED` (before `CLOSED`) |

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
  jpa:
    open-in-view: false
  servlet:
    multipart:
      max-file-size: 1MB
      max-request-size: 5MB
jwt:
  access-token-ttl: 30m
  refresh-token-ttl: 7d
app:
  complaint:
    default-sla-hours: 24
    max-images: 3
```

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
app:
  storage:
    type: local
    local-path: ./uploads
  sms:
    provider: console
  notification:
    push: mock
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
| **Search/filter** | Composite indexes on `(status, substation_id, created_at)`. |
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
# 1. start infra
docker-compose up -d postgres redis

# 2. run app
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. open Swagger
open http://localhost:8080/swagger-ui.html
```

`docker-compose.yml` will provide:
- `postgres:16-alpine` on 5432
- `redis:7-alpine` on 6379
- optional: `prom/prometheus` + `grafana/grafana`

---

## 14. Testing Strategy

| Type | Tool | Scope |
|------|------|-------|
| Unit | JUnit 5 + Mockito | Services, validators, utils |
| Integration | Spring Boot Test + Testcontainers (Postgres, Redis) | Repositories + service flows |
| API Contract | MockMvc / RestAssured | Controllers + security |
| E2E | Selenium | Critical user journeys (web) |
| Load (v2) | k6 or Gatling | RPS targets |

Target coverage: **80%+** for services and controllers.

---

## 15. External Data Sync (Electricity Board System)

The system relies on the external EB system for **consumer** and **station/substation** master data.

### 15.1 Initial Bulk Dump (one-time, before go-live)
- EB team provides CSV / SQL dump of consumers, stations, substations.
- Loaded via a one-off Flyway repeatable migration (`R__bootstrap_eb_data.sql`) OR an admin-triggered loader endpoint.
- Validated record counts + sample spot-checks before opening the system to consumers.

### 15.2 Periodic Incremental Sync (post go-live) — **Approach TBD**

We will pick **one** of the following based on what the EB system can offer:

| Option | When to choose | Pros | Cons |
|--------|----------------|------|------|
| **A. Spring Batch job** | EB exposes a daily CSV / DB snapshot / SFTP dump | Robust restart/retry, chunk-based, good for large dumps, well-suited to nightly windows | More boilerplate, needs scheduler |
| **B. REST API pull** | EB exposes paginated REST endpoints (e.g. `/changes?since=...`) | Near real-time, simpler code, easy to make idempotent | Couples uptime to EB API; need backoff/retry |
| **C. Webhook push from EB** | EB can call us on every change | Truly real-time, minimal polling cost | Requires EB team's effort; needs HMAC signature verification |

### 15.3 Package: `datasync`
- `EbSystemClient` — REST client (option B/C).
- `ConsumerSyncJob` / `SubstationSyncJob` — Spring Batch jobs (option A).
- `DataSyncService` — common upsert logic (idempotent: insert-or-update on `consumer_id` / `substation.code`).
- `DataSyncScheduler` — `@Scheduled(cron = "0 0 2 * * *")` triggers nightly sync (option A/B).
- `SyncAuditLog` — per-run summary: rows read / inserted / updated / failed.

### 15.4 Idempotency & Safety
- All upserts use natural keys (`consumer_id`, `substation.code`, `station.code`).
- Sync **never deletes** — uses a soft `active` flag instead, to avoid orphaning complaints.
- Per-run audit row written to `audit_log` (`target_type = 'DATA_SYNC'`).
- Failures alert via Cloud Monitoring (prod) / log warning (dev/test).

### 15.5 Decision Timeline
Final approach to be locked in **Phase 2** once the EB system's data-sharing capability is confirmed by their team. Until then, the `datasync` package will house only interfaces + a stub implementation.

---

## 16. Open Items / V2 Roadmap

- Auto-assignment of complaints based on substation + technician availability.
- Reporting & analytics dashboards.
- Multi-level escalation (Engineer → Admin → ...).
- Consumer mobile app.
- Real-time API integration with external EB system (currently bulk dump).
- Geo-location auto-detect on complaint form.
- SMS + Email notifications.
- WhatsApp Business API integration.
- Cloud SQL read replicas for reporting.
- Distributed tracing.

