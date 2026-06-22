# Stage 21 — Device Token & Push Notification Contract (DRAFT)

> Status: **draft for FE sign-off**, June 2026.
> Owner: BE. Sibling FE doc lives in `complaints-frontend/docs/` once we agree the shape.
> Once frozen, this doc is the source of truth for Stage 21.1 (persistence + endpoints) and
> Stage 21.2 (provider + listeners). Implementation does not start until the FE confirms.

---

## 1. Scope

Stage 21 introduces **push notifications** to the Complaint Resolution System. In scope:

- A **device-token registry** keyed by `(principal, device_id)` — one per (consumer, device) and
  one per (staff user, device).
- Two REST surfaces: `POST/DELETE /api/v1/consumer/devices/**` and
  `POST/DELETE /api/v1/staff/devices/**`.
- A push **provider abstraction** (`PushService`) with two implementations: console
  (dev / test) and Firebase Cloud Messaging (prod).
- **AFTER_COMMIT listeners** on every Stage 20 `ComplaintEvent` that map events → recipient
  set → push payload, with per-token failure isolation.

Out of scope (separate stages):

- **Stage 22** — persisted in-app notifications, per-user read state, inbox screen.
- **Deep-link routing** — payload carries identifiers only; FE owns the
  `{type → screen}` map.
- **SMS fallback** for consumers without a device token — opt-in in Stage 21.2 behind a
  property flag, full design in a later stage.
- **Localisation of notification copy** — Phase 7 concern; English-only for v1.

---

## 2. Identity model

### 2.1 Why a device-token row instead of "the JWT carries it"

The consumer JWT is **5 minutes, non-refreshable, per-action** (BRD §6). Push subscriptions
must outlive that. Staff JWTs are longer but still rotate. We therefore need a **separate
device registry** that lives on its own lifecycle, independent of any token TTL.

### 2.2 Principal binding

A device-token row is bound to **exactly one** principal:

| Principal | Column on `device_token`                 | Lookup path                          |
|-----------|------------------------------------------|--------------------------------------|
| Consumer  | `consumer_master_id` (FK, nullable)      | via 5-min consumer-verify JWT        |
| Staff     | `user_id` (FK, nullable)                 | via staff JWT + password-reset gate  |

Enforced by a DB `CHECK` that exactly one of the two columns is non-null. There is **no**
shared "anonymous device" path — registration is always after the principal has been
verified.

### 2.3 Device identity

`device_id` is a **client-generated UUID** stored by the FE in:

- iOS: keychain (`SecureStore`) — survives reinstall.
- Android: encrypted shared prefs — survives reinstall on Android 12+.
- Web: `localStorage` — does not survive incognito / clearing site data; that's accepted.

The FE generates the UUID on first launch and reuses it forever. Re-registering the same
`device_id` with a new `push_token` is the **token-rotation** path (FCM / APNs rotate
tokens silently and we must not orphan the old row).

### 2.4 Multi-account on one device

A real scenario: a household device used by a consumer for their own complaints and by a
field engineer logging into the staff app. Both can co-exist:

- `(consumer_master_id=42, device_id=ABC)` — consumer row.
- `(user_id=17, device_id=ABC)` — staff row.

Same `device_id`, different principal rows. The partial-unique index is on
**`(principal_kind, device_id) WHERE active`**, not on `device_id` alone.

---

## 3. REST surface

All endpoints return the standard `ApiResponse<T>` envelope. All paths are under
`/api/v1`. Operation IDs are stable; orval will generate
`registerConsumerDevice` / `revokeConsumerDevice` / `registerStaffDevice` / `revokeStaffDevice`.

### 3.1 Consumer registration

```
POST /api/v1/consumer/devices
```

**Auth**: `ConsumerVerificationFilter` (5-min consumer-verify JWT).
**Idempotency**: re-posting the same `device_id` for the same consumer is a **refresh** —
the row is updated in place, `active=true`, `push_token` overwritten. Returns **200** on
refresh, **201** on first registration. Orval handles both.

**Request body**:

```jsonc
{
  "deviceId":   "550e8400-e29b-41d4-a716-446655440000",   // FE-generated UUID, required, ≤ 64 chars
  "platform":   "ANDROID",                                  // enum: ANDROID | IOS | WEB, required
  "pushToken":  "fcm_token_string_…",                        // raw provider token, required, ≤ 4096 chars
  "appVersion": "1.4.0"                                     // optional, ≤ 32 chars, useful for staged rollout
}
```

**Response body** (`DeviceTokenResponse`):

```jsonc
{
  "id":          12345,
  "deviceId":    "550e8400-e29b-41d4-a716-446655440000",
  "platform":    "ANDROID",
  "appVersion":  "1.4.0",
  "active":      true,
  "registeredAt":"2026-06-23T16:42:11.123+05:30",   // OffsetDateTime IST
  "updatedAt":   "2026-06-23T16:42:11.123+05:30"
}
```

`pushToken` is **never** returned (security: avoid log / cache leakage on the FE side).

**Error codes**:

- `VALIDATION_FAILED` — missing / oversized / wrong-shape field.
- `DEVICE_PLATFORM_UNSUPPORTED` — platform not in enum.
- Standard `UNAUTHORIZED` if the consumer-verify JWT is missing / expired.

### 3.2 Consumer revoke

```
DELETE /api/v1/consumer/devices/{deviceId}
```

**Auth**: same consumer-verify JWT.
**Semantics**: flips `active=false`. We **do not** hard-delete — the row remains for
audit + so a future re-register on the same device reuses the same row.
**Idempotency**: deleting an already-inactive / non-existent device returns **204** (no
body). Foreign device → **403** `DEVICE_NOT_OWNED_BY_CONSUMER` (uniform with our
existing privacy pattern — never leak existence to non-owners).

### 3.3 Staff registration / revoke

```
POST   /api/v1/staff/devices
DELETE /api/v1/staff/devices/{deviceId}
```

**Auth**: `JwtAuthFilter` + `PasswordResetRequiredFilter`.
**Same shapes, same idempotency rules.** Forbidden code is `DEVICE_NOT_OWNED_BY_USER`.

### 3.4 Why no "list my devices" endpoint in Stage 21

Deliberately deferred. The FE only needs register + revoke for the current device. A
"manage devices on other phones" screen is a Stage-22+ feature once we know whether MSEB
operations actually want it. Easy to add later; nothing else depends on the absence.

---

## 4. Wire payload (FCM `data` message)

Stage 21 sends **data-only** FCM messages (no `notification` block). Rationale:

- Lets the FE control display timing (foreground vs background) and grouping.
- No iOS-vs-Android divergence in title / body localisation handling.
- Plays well with React Native Firebase's `setBackgroundMessageHandler`.

**Schema** (locked for v1):

```jsonc
{
  "type":         "COMPLAINT_ASSIGNED",      // see §5 for the enum
  "ticketNo":     "MH20260600000007",
  "complaintId":  "7",                       // sent as string — FCM data values must be strings
  "title":        "New complaint assigned",  // English, server-rendered
  "body":         "Ticket MH20260600000007 - HIGH severity",
  "schemaVersion":"1"                        // bumped if we ever break the shape
}
```

- All values are strings (FCM constraint).
- `title` and `body` are English-only in v1. **If FE wants `titleKey` / `bodyKey` /
  `args` for client-side rendering, this is the moment to say so** — the schema bump is
  cheaper now than after rollout.
- `schemaVersion` lets the FE fall back to a generic banner if a future BE version
  introduces a field the installed app can't parse.

No deep-link URL in the payload. FE owns the `type → screen + params` map. The
identifiers (`ticketNo`, `complaintId`) are everything needed for routing.

---

## 5. Event → recipient policy

The Stage 20 `ComplaintEvent` hierarchy is the trigger surface. Stage 21.2 implements
exactly this table, one `@TransactionalEventListener(phase = AFTER_COMMIT)` method per
event type:

| Event                          | Push recipients                                          | `type` enum                 | Notes                                                                                  |
|--------------------------------|----------------------------------------------------------|-----------------------------|----------------------------------------------------------------------------------------|
| `ComplaintSubmittedEvent`      | active engineer for the receiving DC                     | `COMPLAINT_SUBMITTED`       | New ticket awaiting triage in your DC.                                                 |
| `ComplaintAssignedEvent`       | assigned technician; cc engineer if severity = HIGH      | `COMPLAINT_ASSIGNED`        | Engineer cc'd only on HIGH to avoid noise.                                             |
| `ComplaintReassignedEvent`     | new technician + previous technician + engineer          | `COMPLAINT_REASSIGNED`      | Previous tech told "no longer yours"; new tech told "yours now".                       |
| `ComplaintResolvedEvent`       | consumer (if registered) + engineer                      | `COMPLAINT_RESOLVED`        | SMS fallback for consumer is Stage 21.2 behind a flag.                                 |
| `ComplaintClosedEvent`         | consumer                                                  | `COMPLAINT_CLOSED`          | "Rate the resolution" — title nudges toward the feedback flow.                         |
| `SlaBreachedEvent`             | assigned technician + assigned engineer                  | `SLA_BREACHED`              | Repeats every 15-min sweep tick if not yet acknowledged? **NO** — once per breach.    |
| `FeedbackSubmittedEvent`       | assigned technician + engineer; admin if rating ≤ 2      | `FEEDBACK_RECEIVED`         | Escalation to admin only on low ratings.                                               |
| `ComplaintCancelledEvent`      | assigned technician (if any)                              | `COMPLAINT_CANCELLED`       | Engineer of the DC if no tech assigned yet.                                            |
| `ComplaintRejectedEvent`       | consumer                                                  | `COMPLAINT_REJECTED`        | "Your complaint was not accepted — reason: …" (reason inlined into `body`).            |

**Recipient resolution** lives in `notification.service` and depends only on
`StaffLookupService` interfaces + `DeviceTokenRepository` — **no** cross-module repository
hops (ArchUnit-enforced).

**Inactive recipients** (e.g. technician deactivated mid-flight): the device-token query
joins `user_account.active = true`. Inactive staff get no push. Stale tokens for inactive
principals are also marked inactive nightly (Stage 21.2 scheduled sweep).

---

## 6. Provider abstraction

```java
public interface PushService {
    void send(DeviceToken target, NotificationPayload payload);
}
```

Two implementations, profile-switched:

- `ConsolePushService` (`@Profile({"dev","test"})`) — logs the payload at INFO. No external
  side effects. Used by ITs.
- `FcmPushService` (`@Profile("prod")` + `@ConditionalOnProperty(prefix="fcm", name="enabled")`)
  — wraps Firebase Admin SDK. Service-account JSON path bound via
  `@ConfigurationProperties(prefix = "fcm")`.

### 6.1 Failure model

- **Transient failures** (network, 5xx from FCM) — logged, **not** retried inline. A
  background retry queue is a Stage 22+ concern; v1 accepts at-most-once delivery and
  trusts the FE to re-fetch on app open.
- **Permanent failures** (`NotRegistered`, `InvalidRegistration`, `MismatchSenderId`) —
  flip `active=false` on the offending `device_token` row, in a fresh
  `REQUIRES_NEW` transaction so one bad token does not poison the rest of the fan-out.
- **Per-recipient isolation** — the listener iterates recipients and catches per-call
  exceptions; one bad token never blocks the next.

### 6.2 What we never log

- The `push_token` value itself.
- Notification `body` content (in case it ever contains PII like names / reasons).
- Only log: `event=`, `ticketNo=`, `complaintId=`, `recipientUserId=`, `platform=`,
  `outcome=SENT|FAILED|TOKEN_INACTIVE`.

---

## 7. Schema

New Flyway migration (next available `V<x.y+1>__create_device_token.sql`). Indicative
DDL — final version reviewed when 21.1 ships:

```sql
CREATE TABLE device_token (
    id                  BIGSERIAL PRIMARY KEY,
    consumer_master_id  BIGINT NULL REFERENCES consumer_master(id),
    user_id             BIGINT NULL REFERENCES user_account(id),
    device_id           VARCHAR(64) NOT NULL,
    platform            VARCHAR(16) NOT NULL,        -- ANDROID | IOS | WEB
    push_token          TEXT NOT NULL,
    app_version         VARCHAR(32) NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_device_token__principal_xor CHECK (
        (consumer_master_id IS NOT NULL) <> (user_id IS NOT NULL)
    )
);

-- One active row per (consumer, device).
CREATE UNIQUE INDEX ux_device_token_consumer_device_active
    ON device_token (consumer_master_id, device_id)
    WHERE active AND consumer_master_id IS NOT NULL;

-- One active row per (staff user, device).
CREATE UNIQUE INDEX ux_device_token_user_device_active
    ON device_token (user_id, device_id)
    WHERE active AND user_id IS NOT NULL;

-- Fan-out lookups.
CREATE INDEX ix_device_token_consumer_active
    ON device_token (consumer_master_id) WHERE active AND consumer_master_id IS NOT NULL;
CREATE INDEX ix_device_token_user_active
    ON device_token (user_id) WHERE active AND user_id IS NOT NULL;
```

Migration is **additive**; nothing in the existing schema changes.

---

## 8. Error codes added in Stage 21

| Code                              | HTTP | Meaning                                                             |
|-----------------------------------|------|---------------------------------------------------------------------|
| `DEVICE_PLATFORM_UNSUPPORTED`     | 400  | `platform` not in `ANDROID | IOS | WEB`.                            |
| `DEVICE_NOT_OWNED_BY_CONSUMER`    | 403  | Revoke / refresh attempted by a different consumer.                 |
| `DEVICE_NOT_OWNED_BY_USER`        | 403  | Revoke / refresh attempted by a different staff user.               |

(`VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN` are reused — no new codes.)

---

## 9. Open questions for the FE — please confirm before BE Stage 21.1 starts

1. **Localisation now or later?** Stage 21 sends English `title` / `body`. If you want
   `titleKey` / `bodyKey` / `args` in the payload, say so before 21.0 freezes — schema
   bump after rollout costs us a `schemaVersion=2` migration window.
2. **Web push?** The contract enumerates `WEB` as a platform but the BE-side
   `FcmPushService` path for web (VAPID, service worker) is a Stage 22 concern. Confirm
   you only need `ANDROID` + `IOS` for v1 launch.
3. **Token rotation cadence** — confirm the FE re-registers on every cold start AND on
   FCM's `onTokenRefresh` callback. Without both, stale tokens silently rot until our
   nightly sweep flips them.
4. **Revoke on logout?** The FE staff-app logout flow should call `DELETE /staff/devices/
   {deviceId}` before clearing the JWT. Same for consumer logout (if such a flow exists
   — given the 5-min JWT it usually doesn't).
5. **Quiet hours / DND?** Out of scope for Stage 21. Confirm OK.
6. **Push permission UX** — FE asks at logical moments (post-submit, post-resolve), not
   on app launch. BE assumes nothing about the prompt — we just receive the token when
   you have one.

---

## 10. Suggested timeline

| Sub-stage    | BE effort | FE can parallel?                                              |
|--------------|-----------|---------------------------------------------------------------|
| 21.0 (this)  | done      | **Sign-off + question answers needed before 21.1 starts.**    |
| 21.1 (schema + endpoints) | ~1 day | Yes — FE can build the registration screen + revoke flow. |
| 21.2 (provider + listeners) | ~1.5 days | Yes — FE wires `setBackgroundMessageHandler` + the type→screen map. |

Total ~2.5 days BE once 21.0 is signed off, fully parallel-friendly.

---

## 11. Versioning

This doc is **version 1 (DRAFT)**. Material changes after FE sign-off bump a version
suffix (`-v2`, `-v3`) at the top and are appended in a changelog below.

### Changelog

- **2026-06-23** — initial draft, BE-side. Awaiting FE sign-off on §3 endpoints, §4
  payload shape, §9 open questions.

