# Business Requirements Document (BRD)
## Complaint Resolution System — State Electricity Board (Maharashtra)

---

## 1. Project Overview

- **Project Name:** Complaint Resolution System
- **Primary Goals:**
  - Manage complaints efficiently and reduce resolution time.
  - Improve transparency in the resolution process.
  - Provide a platform for users to submit complaints, track status, and receive updates.
  - Facilitate communication between consumers and authorities.
- **Key Stakeholders:** Consumers (Users), Admins, Engineers, Technicians.

---

## 2. Scope

### In Scope
- Complaint creation
- Complaint tracking
- Complaint resolution and closure
- Notifications (in-app + push)
- User authentication (consumer self-registration with OTP, staff approval flow)
- Subdivision / Distribution Center master data management

### Out of Scope (v1)
- Billing disputes
- Meter replacement requests
- Reporting & analytics dashboards (deferred to v2)
- SMS / Email notifications (in-app only for v1; SMS used only for OTP)
- Auto-assignment of complaints (deferred to v2 — manual assignment only)
- Multi-level escalation (single level only — engineer)
- Auto-detection of consumer location (deferred to v2)
- Complaint re-open by consumer (deferred — consumer raises a new complaint instead)
- Hierarchy above Subdivision Office (Division/Circle/Zone — deferred to v2)

### Geographic Coverage
- **Subdivisions** and **Distribution Centers** under the State Electricity Board of **Maharashtra**.
- Business hierarchy (v1):
  ```
  Subdivision Office (taluka)   ←  Admin   (Deputy Executive Engineer)
       └── Distribution Center  ←  Engineer
                └── Technicians
  ```

### Platforms
- **Web Portal** — Consumers, Admins, Engineers
- **Mobile App (React Native, iOS + Android)** — Technicians, Engineers (v1)
  - Consumer mobile app: v2

---

## 3. Stakeholders & User Roles

| Role | Real-world equivalent | Scope | Permissions |
|------|----------------------|-------|-------------|
| **Consumer** | End user with an electricity connection. | Self (own complaints only) | **No registration, no login.** Each time they use the app/portal, they enter their **Consumer ID + Mobile Number** and verify via **OTP** for that session. They can then: submit complaints, view their complaints, cancel (only while status = SUBMITTED), provide feedback. Mobile number is mandatory on every submission. |
| **Technician** | On-ground technician under a Distribution Center. | Own assigned complaints | View assigned complaints, update status, upload resolution images, close complaint (must give reason if SLA breached). Logs in with **Employee ID + Password**. |
| **Engineer** | Engineer at a **Distribution Center** (exactly **one engineer per DC**). | Their Distribution Center | View all complaints in their DC, **set severity** (Low / Medium / High), manually assign / **reassign** to a technician in their DC, **register new technicians under their DC**, receive SLA breach alerts, monitor resolution, reject / mark duplicate. Logs in with **Employee ID + Password**. |
| **Admin** | **Deputy Executive Engineer** at a **Subdivision Office** (exactly **one admin per Subdivision**). | Their Subdivision (all DCs under it) | **Register engineers and technicians** in their subdivision, manage subdivision/DC/category master data, update SLA configuration, manage users in scope, **reassign complaints across DCs within their subdivision**, oversee resolution. Logs in with **Employee ID + Password**. |

> Admins are **not self-registered** — the first Admin is bootstrapped via env vars (`BOOTSTRAP_ADMIN_EMPLOYEE_ID` / `BOOTSTRAP_ADMIN_PASSWORD` / `BOOTSTRAP_ADMIN_SUBDIVISION_CODE`) on first app boot, and any additional Admins are inserted directly into the DB by a DBA.

### Access Channels
- Web Portal (all roles)
- Mobile App (Technicians, Engineers in v1)

---

## 4. Functional Requirements

### 4.1 Consumer Identity Verification (No Registration, No Login)

Consumers do **not** create an account, do **not** have a password, and do **not** log in. Every consumer-side action — submitting a complaint, viewing complaints, cancelling, leaving feedback — requires the consumer to prove ownership of the connection **each time**, via:

1. **Consumer ID** — validated against the `consumer_master` table (loaded from external EB system via the `datasync` module).
   - If the Consumer ID is **not found** or `active = false` → error: *"Consumer ID not found. Please contact your local Subdivision office."*
2. **Mobile Number** — mandatory, free-form. Need not match the on-file mobile in `consumer_master` (a relative / neighbour / shop owner may raise the complaint on the consumer's behalf).
3. **OTP** — sent to the mobile number entered in step 2. On verification the system issues a short-lived **session/verification JWT** (5 min) that is required by all subsequent consumer endpoints in that session.

> **App / Portal UX:** The landing screen shows a **"Consumer"** entry point (no login wall). Tapping it leads to a single form (Consumer ID + Mobile + OTP) which, once verified, unlocks **two options**: *Submit a complaint* and *View my complaints*. The verification token expires in 5 minutes; the consumer simply re-verifies if it expires.

### 4.2 Staff (Admin / Engineer / Technician) Authentication
- **Staff cannot self-register.** Accounts are created by authorized users only:
  - **Admin** accounts are created **directly in the database** — the first admin via the `AuthBootstrapRunner` (env vars on first boot); subsequent admins via a DBA SQL insert. There is no admin self-registration API in v1 and no admin-creates-admin API. **Exactly one active admin per Subdivision Office.**
  - **Engineer** accounts can be created **only by an Admin**, scoped to a DC within the admin's subdivision. `subdivision_id` + `distribution_center_id` are both mandatory. **Exactly one active engineer per Distribution Center.**
  - **Technician** accounts can be created by **an Admin or an Engineer** (many technicians per DC):
    - Admin → any DC under the admin's subdivision (`subdivision_id` + `distribution_center_id` both mandatory).
    - Engineer → **only within the engineer's own DC** (scope is derived from the engineer's account, not accepted from the request body).
- **Login identifier:** every staff user logs in with their **Employee ID** + Password. Email is captured for contact but is **not** used as the login identifier.
- **Password setup:**
  - The creator (Admin or Engineer) **types an initial password** in the create-staff form.
  - The new staff account is created with `password_reset_required = true`.
  - On first login, the staff user is **forced to change the password** before any other API will accept their access token.
- New staff accounts are created with `enabled = true` (no separate approval step — creation by an authorized actor is the approval).
- Admin or Engineer can later **disable / re-enable** staff accounts in their scope.

### 4.3 Session Management
- **JWT-based stateless authentication** (staff only).
- **Staff access token:** 30 minutes; **staff refresh token:** 7 days (stored hashed in `refresh_token` table for revocation).
- **Consumer verification token:** 5 minutes, single-purpose, **non-refreshable**. Consumer simply re-runs the Consumer ID + Mobile + OTP flow when it expires.

### 4.4 Complaint Creation
- Any consumer-side action **requires a valid consumer verification token** (issued by `/auth/consumer/otp/verify`) — there is no consumer login, no consumer password.
- Submission channels: Web portal, mobile app (consumer entry point, no login).
- One consumer (one Consumer ID) can submit **multiple complaints**.
- Each complaint **captures**:
  - **Consumer ID** (validated against `consumer_master`).
  - **Contact Mobile** — mandatory; the mobile that was OTP-verified for this submission. Stored on the complaint so the field team can call back.
  - **Category** (Power Outage, Low Voltage, Transformer Fault, Other) — picked by consumer.
  - **Description** (free text).
  - **Location** (manual entry for v1; auto-detect in v2).
  - **Images:** Max **3 images, 1 MB each**, **compressed** before storage.
- **Severity** (Low / Medium / High) is **NOT picked by the consumer** — it is set by the **Engineer** during analysis/assignment.
- The complaint is auto-routed to the **Distribution Center** mapped to the consumer in `consumer_master`.
- **SLA:** Default 24 hours per category; admin-configurable in `sla_config`.

> **Technicians** can also upload images at the time of resolution as **proof of work** (same limits: max 3 images, 1 MB each, compressed). Resolution images are stored separately from consumer-submitted complaint images so both sets can be viewed independently.

### 4.5 Viewing / Tracking Complaints (Consumer)
- The consumer goes through the same Consumer ID + Mobile + OTP flow and then taps **"View my complaints"**.
- The list returned shows **all complaints ever filed against that Consumer ID**, regardless of which mobile number was used at submission time (since complaints are anchored to Consumer ID, not to mobile).
- A complaint detail view shows current status, history, complaint images, resolution images, assigned technician name, and SLA deadline.
- A consumer can **cancel** a complaint from this view — but only while the status is still `SUBMITTED` (not yet assigned/in-progress). A cancellation **reason is required**. No time window — purely status-based.
- A consumer can submit **feedback** (rating + comment) on a `CLOSED` complaint from this view (one-shot, cannot edit).

### 4.6 Complaint Management & Tracking
- **Engineer** analyzes complaints in their Distribution Center, **sets severity** (Low/Medium/High), and **manually assigns** to a Technician in their DC (auto-assignment in v2). Engineer can **reassign** the complaint to a different technician **within the same DC**.
- **Admin** can oversee and reassign complaints **anywhere within their Subdivision** — i.e., they may reassign a complaint from one DC to another DC (with a different engineer + technician) inside the same subdivision.
- **Technician** views and updates the status of complaints **assigned to them only**.
- **Consumer** can track status by re-entering Consumer ID + Mobile + OTP at any time. **Cancellation** is allowed only while the status is `SUBMITTED` — no time window, status-based only — and a reason is required.
- Consumers **cannot re-open** a closed complaint in v1 — they must raise a **new complaint** instead.
- Each complaint gets a unique **ticket number** of the form `MH<YYYY><MM><8-digit-seq>` (e.g. `MH2026060000123`). The 8-digit sequence resets every month.

### 4.7 Complaint Status Lifecycle
```
SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED

Alternate / terminal states:
- CANCELLED  (by consumer, only when status = SUBMITTED, requires reason)
- REJECTED   (by engineer/admin, requires reason)
- DUPLICATE  (by engineer/admin, must reference parent complaint)

No RE-OPENED in v1 — consumer raises a new complaint instead.
```

### 4.8 Complaint Resolution & Closure
- The **Technician** is the normal closing actor: resolves, provides resolution details, optionally uploads up to 3 proof-of-work images (1 MB each, optional), then closes.
- The **Engineer** (or **Admin**) can **also close** a complaint on behalf of the technician — for example when the technician is unavailable, or when the SLA was breached **before** the complaint was even assigned (engineer triage delay) and needs to be closed-with-reason.
- **Resolution images are optional** in v1 (max 3, 1 MB each, compressed).
- **SLA breach reason** — required from **whoever closes** the complaint when `sla_breached = true` (technician, engineer, or admin), if it hasn't already been recorded.
- Consumer can submit **feedback** (rating + comment) after closure (one-shot, not editable).

### 4.9 SLA & Escalation
- SLA default: 24 hours (admin-configurable per category).
- **On SLA breach:** alert sent to the assigned **Engineer**.
- **Escalation:** single level — Engineer only (multi-level deferred to v2).

### 4.10 Notifications
- **v1 Channels:** In-app notifications (DB-backed) + Push notifications (Firebase Cloud Messaging for the staff mobile app).
- **Audience:** Notifications are sent **only to staff** (Admin, Engineer, Technician) — they are the ones with logged-in app sessions / device tokens.
- **Consumers do not receive push or SMS status notifications in v1** (they have no account, no app session, no registered device token). Consumers track status by re-entering Consumer ID + Mobile + OTP whenever they want an update. SMS to consumers for status updates is deferred to v2.
- **Events that trigger staff notifications:**
  - Complaint submitted in DC (to engineer of that DC)
  - Complaint assigned (to technician)
  - Complaint reassigned (to new technician + the engineer/admin who reassigned)
  - SLA breach (to engineer; escalated to admin in v2)
- Staff can **opt-out** of push (in-app entries are always recorded).

### 4.11 Subdivision / Distribution Center Master Data
- Stored in dedicated `subdivision` and `distribution_center` tables (one-to-many: Subdivision → DCs).
- Initial dump from the external Electricity Board system via the `datasync` module.
- Admin can **add / edit / soft-delete** subdivisions and DCs via UI (`active = false` instead of hard delete to preserve historical complaints).
- **Cascade on soft-delete:**
  - Soft-deleting a **Distribution Center** auto-disables (`enabled = false`) every Engineer and Technician assigned to that DC. Open complaints in that DC are left untouched (for history); the admin is expected to reassign them across DCs *before* deactivating, if continued operation is required.
  - Soft-deleting a **Subdivision** soft-deletes every DC under it and disables every staff (admin/engineer/technician) in scope.
- Each consumer in `consumer_master` is **already mapped to a Distribution Center** in the external system.
- Each complaint is auto-routed to the consumer's DC for engineer triage.

### 4.12 Reporting (Deferred to v2)
- Skipped for v1.

---

## 5. Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | Support 1M consumers, ~10K complaints/day. |
| **Scalability** | Horizontally scalable (stateless services, Kubernetes HPA in prod). |
| **Security** | JWT auth, encrypted passwords (BCrypt), HTTPS only in prod, role-based access control (RBAC), OWASP top 10 hardening. |
| **Usability** | Simple, intuitive UI for consumers; minimal friction for complaint submission. |
| **Reliability** | Target uptime 99.5% in prod; daily DB backups. |
| **Accessibility** | Multilingual support: English, Hindi, Marathi (frontend i18n). |
| **Compliance** | DLT compliance for OTP SMS (MSG91 production). |

---

## 6. Integration Requirements

- **External Electricity Board System** — source of truth for **consumer master + subdivision/distribution-center** data.
  - **Initial bulk dump** on go-live (one-time load into `consumer_master`, `subdivision`, `distribution_center`).
  - **Periodic incremental sync** after go-live (e.g., daily/hourly). Implementation approach is **not yet decided** — candidates:
    - **Spring Batch** job (scheduled via `@Scheduled` or external scheduler) reading from a flat file / DB / API.
    - **REST API integration** (pull from EB system endpoints on a schedule, or push webhook from EB).
  - Decision to be made during Phase 2 once the EB system's data-sharing capability is confirmed.
- **MSG91** — SMS gateway for OTPs (DLT-registered in prod, sandbox in dev/test).
- **Firebase Cloud Messaging (FCM)** — Push notifications to mobile app.

---

## 7. Technical Stack Summary

| Layer | Tool |
|-------|------|
| Language | Java 21 |
| Backend Framework | Spring Boot 4.1 |
| Security | Spring Security + JWT |
| ORM | Spring Data JPA + Hibernate |
| DB Migrations | Flyway |
| Build | Maven |
| API Docs | springdoc-openapi (Swagger) |
| Database | PostgreSQL |
| Cache | Caffeine (in-JVM); Memorystore Redis only when scaling beyond 1 pod |
| DTO Mapping | Hand-written `*Mapper` classes (no MapStruct) |
| Web | React |
| Mobile | React Native (iOS + Android) |
| Cloud | GCP (Mumbai `asia-south1`) |
| Container | Docker, Docker Compose |
| Orchestration (prod) | Kubernetes — GKE Autopilot |
| Object Storage | GCP Cloud Storage |
| Message Queue | GCP Pub/Sub (Spring Events in dev) |
| Monitoring | GCP Cloud Operations (Logging + Monitoring) |
| CI/CD | GitHub Actions |
| Testing | JUnit 5, Mockito, Testcontainers, Selenium |

> Detailed environment-wise breakdown: see [TECH_STACK.md](./TECH_STACK.md).

---

## 8. Environments

| Environment | Purpose | Cost | Status |
|-------------|---------|------|--------|
| **Dev** | Local development on laptop (Docker Compose) | $0/month | Phase 1 |
| **Test** | Cloud-hosted on single GCP VM for QA/UAT | ~$15/month | Phase 2 |
| **Prod** | GKE Autopilot + managed GCP services | ~$700-1,100/month | Phase 3 (later) |

---

## 9. Glossary

| Term | Meaning |
|------|---------|
| **Consumer ID** | Unique electricity connection identifier issued by the State Electricity Board. |
| **Employee ID** | Login identifier issued to staff (Admin / Engineer / Technician). Used together with a password for staff login. |
| **Subdivision Office** | Taluka-level office, managed by an Admin (DEE). Contains multiple Distribution Centers. |
| **Distribution Center (DC)** | Operational unit under a Subdivision; managed by an Engineer; technicians work under it. |
| **DEE** | Deputy Executive Engineer — runs a Subdivision Office; mapped to the `ADMIN` role in v1. |
| **Consumer Master** | Read-only local copy of consumer data sourced from the external EB system (`consumer_master` table). |
| **Ticket Number** | Human-readable complaint identifier: `MH<YYYY><MM><8-digit-seq>` (sequence resets monthly). |
| **SLA** | Service Level Agreement — max allowed time to resolve a complaint. |
| **DLT** | Distributed Ledger Technology — Indian telecom regulation for SMS sender registration. |
| **GKE** | Google Kubernetes Engine. |
| **FCM** | Firebase Cloud Messaging — Google's push notification service. |
| **OTP** | One-Time Password sent via SMS. Used to verify the mobile number of a consumer before every consumer-side action (submit / view / cancel / feedback). (A separate OTP purpose is reserved for a self-service staff password-reset flow planned for v2.) |
