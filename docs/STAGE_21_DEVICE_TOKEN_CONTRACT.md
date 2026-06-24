# Stage 21 — Device Token & Push Notification Contract (v1.0 FROZEN)

> Status: **FROZEN — implementing in Stage 21.1**, 2026-06-25.
> Owner: BE. Sibling FE copy lives at `complaints-frontend/docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md`
> and is the same source of truth — both sides update together.
> FE sign-off received 2026-06-25 with two deltas folded into §4 and §8 below.

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
  "type":            "COMPLAINT_ASSIGNED",      // see 5 for the enum
  "ticketNo":        "MH20260600000007",
  "complaintId":     "7",                       // sent as string — FCM data values must be strings
  "title":           "New complaint assigned",  // English, server-rendered
  "body":            "Ticket MH20260600000007 - HIGH severity",
  "eventOccurredAt": "2026-06-25T18:42:11+05:30", // ISO-8601 IST, server clock at AFTER_COMMIT
  "schemaVersion":   "1"                        // bumped if we ever break the shape
}
```

- All values are strings (FCM constraint).
- `title` and `body` are English-only in v1 (FE sign-off §9.1: defer localisation to
  Stage 22's persisted inbox; bump to `schemaVersion=2` when added).
- **`eventOccurredAt`** is the server's IST timestamp captured at the moment the
  `AFTER_COMMIT` listener fires — *not* the FCM send time. Lets the FE render correct
  "n minutes ago" labels when a batch is delivered after the device was offline / killed,
  and de-dupe against any inbox snapshot pulled on resume (added per FE sign-off 2026-06-25).
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
| `DEVICE_PLATFORM_UNSUPPORTED`     | 400  | `platform` not in `ANDROID \| IOS \| WEB`.                          |
| `DEVICE_NOT_OWNED_BY_CONSUMER`    | 403  | Revoke / refresh attempted by a different consumer.                 |
| `DEVICE_NOT_OWNED_BY_USER`        | 403  | Revoke / refresh attempted by a different staff user.               |
| `INVALID_PUSH_TOKEN_FORMAT`       | 400  | Push token fails FCM / APNs shape validation. Distinct from `VALIDATION_FAILED` so FE can trigger a "fetch a fresh token and retry once" path. (Reserved per FE sign-off 2026-06-25.) |
| `DEVICE_TOKEN_LIMIT_EXCEEDED`     | 409  | Per-principal device cap exceeded. No cap is enforced in Stage 21.1; the code is reserved so FE can render "Too many devices, revoke one in settings" without a contract bump when the cap lands. (Reserved per FE sign-off 2026-06-25.) |

(`VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN` are reused — no new codes there.)

Both new codes must be mirrored in `@complaints/i18n` `errorCodes.*` keys at the same
time Stage 21.1 ships, so the generated `@complaints/api` types pick them up.

---

## 9. Resolved decisions (was: open questions for the FE)

Frozen 2026-06-25 — FE sign-off received. Original questions kept inline for traceability.

| # | Question | Decision | Rationale (FE) |
|---|----------|----------|----------------|
| 9.1 | Localisation now or later? | **Later.** English-only `title` / `body` in v1. Do **not** add `titleKey` / `bodyKey` / `args` yet. | Mobile app lands Phase 4+; Stage 22's persisted-inbox row will be the display-string source of truth — localise *there*, not in the FCM data frame. Bump to `schemaVersion=2` when added. |
| 9.2 | Web push? | **No web push in v1.** `ANDROID` + `IOS` only. Keep `WEB` in the platform enum so the contract doesn't churn, but BE ships no VAPID / service-worker path. | Consumer PWA has 5-min JWT (no anchor principal for push); staff portal users are at desks with the tab open. Defer to Stage 22+ when an inbox backs it. |
| 9.3 | Token rotation cadence? | **Confirmed.** FE re-registers on (a) every cold start and (b) FCM `onTokenRefresh` / Expo `addPushTokenListener`. Same `device_id`, new `push_token` → refresh path (200). Never `DELETE`+`POST`. | §3.1 idempotent upsert. `DELETE`+`POST` would momentarily break the partial-unique index and lose `created_at` audit. Cold-start re-register is cheap insurance against missed `onTokenRefresh` events. |
| 9.4 | Revoke on logout? | **Staff: yes** — `DELETE /staff/devices/{deviceId}` before clearing the JWT in the logout reducer. **Consumer: no explicit revoke** — the 5-min verify JWT expires on its own; the row stays until a different consumer registers on the same `device_id` or the user uninstalls. | Matches the `authStore` / `consumerAuthStore` split — staff has a real logout button, consumer doesn't. Failed `DELETE` on logout must **not** block JWT clear (best-effort, fire-and-forget with a timeout). |
| 9.5 | Quiet hours / DND? | **Out of scope.** OS-level DND is sufficient for v1; in-app quiet-hours preferences belong in Stage 22 alongside the inbox / preferences screen. | — |
| 9.6 | Push permission UX | **FE prompts at logical moments, never on launch.** Consumer: after first successful complaint submit. Staff: after first successful login post password-change gate. If denied: FE does **not** call `POST /devices` (no token to send); re-prompts at most once per 7 days (tracked client-side). If permission is *revoked later*: FE detects on next launch via `getPermissionsAsync()` and **does** call `DELETE /devices/{deviceId}` so BE stops fanning out to a dead token before the nightly sweep catches it. | No iOS App Store reviewer passes cold-launch prompts; the "we'll notify you when assigned" toast post-submit is the natural lead-in. |

**Additional confirmations from FE sign-off** (no contract change required, recorded for trail):

- **`device_id` storage primitives** — iOS: `expo-secure-store` (keychain,
  `WHEN_UNLOCKED_THIS_DEVICE_ONLY`) — survives reinstall. Android: `expo-secure-store`
  (wraps `EncryptedSharedPreferences` on API 23+) — survives reinstall on Android 12+
  per §2.3. Web: `localStorage` under key `crs.deviceId` — lost on incognito / "Clear
  site data" / cross-browser; a fresh UUID just creates a new device row and the old one
  ages out via the nightly sweep.
- **Multi-account on one device** — `(principal_kind, device_id)` model per §2.4 / §7
  works as-is. Shared `device_id` across both auth stores; principal binding is server-side
  at registration time via whichever JWT is presented. `authStore` and `consumerAuthStore`
  each track their own `lastRegisteredDeviceId` flag for session-resume re-register.
- **§5 SLA breach: once per breach, not per sweep tick** — confirmed correct call; FE has
  no client-side de-dupe layer in v1.
- **§6.2 never-log list** — FE will mirror in the Sentry `beforeSend` filter when Phase 7
  observability lands.

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

This doc is **v1.0 FROZEN** as of 2026-06-25. Material changes after freeze bump a
version suffix (`v1.1`, `v2.0`) at the top and are appended to the changelog below.
Both the BE copy (`complaints/docs/`) and the FE copy
(`complaints-frontend/docs/`) update together.

### Changelog

- **2026-06-25 — v1.0 FROZEN.** FE sign-off received. Two deltas folded in:
  (1) §4 payload gains `eventOccurredAt` (ISO-8601 IST string, server clock at
  `AFTER_COMMIT`); (2) §8 reserves `INVALID_PUSH_TOKEN_FORMAT` (400) and
  `DEVICE_TOKEN_LIMIT_EXCEEDED` (409). §9 questions resolved with decisions recorded
  inline. BE Stage 21.1 unblocked.
- **2026-06-23** — initial draft, BE-side. Awaiting FE sign-off on 3 endpoints, 4
  payload shape, 9 open questions.

