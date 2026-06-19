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
- **Key Stakeholders:** Consumers (Users), Admins, Engineers, Field Technicians.

---

## 2. Scope

### In Scope
- Complaint creation
- Complaint tracking
- Complaint resolution and closure
- Notifications (in-app + push)
- User authentication (consumer self-registration, staff approval flow)
- Station/Substation master data management

### Out of Scope (v1)
- Billing disputes
- Meter replacement requests
- Reporting & analytics dashboards (deferred to v2)
- SMS / Email notifications (in-app only for v1)
- Auto-assignment of complaints (deferred to v2 — manual assignment only)
- Multi-level escalation (single level only — engineer)
- Auto-detection of consumer location (deferred to v2)

### Geographic Coverage
- Stations and sub-stations under the State Electricity Board of **Maharashtra**.

### Platforms
- **Web Portal** — Consumers, Admins, Engineers
- **Mobile App (React Native, iOS + Android)** — Field Technicians, Engineers (v1)
  - Consumer mobile app: v2

---

## 3. Stakeholders & User Roles

| Role | Description | Permissions |
|------|-------------|-------------|
| **Consumer** | End user with an electricity connection. | Register, login, submit complaints (logged in or guest), track status, cancel (only if not yet assigned), provide feedback, opt-in/out of notifications. |
| **Field Technician** | Resolves complaints on the ground. | View assigned complaints, update status, communicate resolution details, close complaint (must give reason if SLA breached). |
| **Engineer** | Analyzes complaints and assigns them to field technicians. | View all complaints in their area, manually assign to technicians, receive SLA breach alerts, monitor resolution. |
| **Admin** | System administrator. | Manage user accounts, approve staff registrations, manage station/substation master data, update SLA configuration, oversee resolution process. |

### Access Channels
- Web Portal (all roles)
- Mobile App (Field Technicians, Engineers in v1)

---

## 4. Functional Requirements

### 4.1 Consumer Registration & Authentication
- Consumer registers using **Consumer ID + Mobile Number + Password**.
- Consumer ID is validated against the **external Electricity Board system** (already exists there).
- Login: Consumer ID + Password.
- **Forgot Password:** OTP-based reset via mobile number.

### 4.2 Staff (Admin / Engineer / Field Technician) Authentication
- Staff submits a **registration request**.
- Admin **approves** the request before the account becomes active.
- Admin can also register staff directly.
- Login: Email/Staff ID + Password.

### 4.3 Session Management
- **JWT-based stateless authentication.**
- Access Token: **30 minutes**.
- Refresh Token: **7 days**.

### 4.4 Complaint Creation
- Consumers can submit complaints when **logged in** or as **guest** (guest must verify mobile via OTP).
- Submission channels: Web portal, mobile app.
- One consumer can submit **multiple complaints**.
- Each complaint has:
  - **Category** (Power Outage, Low Voltage, Transformer Fault, Other, etc.)
  - **Severity** (Low / Medium / High / Critical)
  - **Description**
  - **Location** (manual entry for v1; auto-detect in v2)
  - **Images:** Max **3 images, 1 MB each**, **compressed** before storage.
- **SLA:** Default 24 hours; admin-configurable.

> **Field Technicians** can also upload images at the time of resolution as **proof of work** (same limits: max 3 images, 1 MB each, compressed). Resolution images are stored separately from consumer-submitted complaint images so both sets can be viewed independently.

### 4.5 Guest Complaint Flow
- Guest provides Consumer ID + Mobile Number.
- System sends OTP to mobile → guest verifies OTP → complaint is submitted.

### 4.6 Complaint Management & Tracking
- **Engineer** analyzes complaints and **manually assigns** to a Field Technician (auto-assignment in v2).
- **Admin** can also manage and reassign complaints.
- **Field Technician** views and updates the status of assigned complaints.
- **Consumer** can track status in **real-time** and **cancel** the complaint — but only if the status is still `SUBMITTED` (not yet assigned or in progress).
- Consumer can re-open a complaint after resolution if not satisfied (within a defined window).

### 4.7 Complaint Status Lifecycle
```
SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED
                                          ↓
                                       RE-OPENED → ASSIGNED → ...

Alternate end states:
- CANCELLED  (by consumer, only when status = SUBMITTED, requires reason)
- REJECTED   (by engineer/admin, requires reason)
- DUPLICATE  (by engineer/admin, must reference parent complaint)
```

### 4.8 Complaint Resolution & Closure
- Field Technician resolves and provides resolution details.
- Field Technician **can upload up to 3 images (1 MB each) as proof of resolution**.
- If SLA is breached, the closing actor (Field Technician) **must provide a reason for late closure**.
- Admin can monitor compliance.
- Consumer can submit **feedback** (rating + comment) after closure.

### 4.9 SLA & Escalation
- SLA default: 24 hours (admin-configurable per category).
- **On SLA breach:** alert sent to the assigned **Engineer**.
- **Escalation:** single level — Engineer only (multi-level deferred to v2).

### 4.10 Notifications
- **v1 Channels:** In-app notifications + Push notifications (Firebase Cloud Messaging for mobile).
- **Events that trigger notifications:**
  - Complaint submitted (ack to consumer)
  - Complaint assigned (to consumer + field technician)
  - Status changed (to consumer)
  - Complaint resolved / closed (to consumer)
  - SLA breach (to engineer)
- Consumers can **opt-out** of notifications.

### 4.11 Station / Substation Master Data
- Stored in dedicated `station` and `substation` tables.
- Initial dump from the external Electricity Board system.
- Admin can **add / edit / remove** stations and substations via UI.
- Each consumer is **already mapped to a substation** in the external system.

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

- **External Electricity Board System** — source of truth for consumer + station/substation data.
  - **Initial bulk dump** on go-live (one-time load into our DB).
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
| Build | Maven |
| API Docs | springdoc-openapi (Swagger) |
| Database | PostgreSQL |
| Cache | Redis (prod only) |
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
| **Substation** | Electrical substation that supplies power to a group of consumers. |
| **Station** | A parent unit that contains multiple substations. |
| **SLA** | Service Level Agreement — max allowed time to resolve a complaint. |
| **DLT** | Distributed Ledger Technology — Indian telecom regulation for SMS sender registration. |
| **GKE** | Google Kubernetes Engine. |
| **FCM** | Firebase Cloud Messaging — Google's push notification service. |

