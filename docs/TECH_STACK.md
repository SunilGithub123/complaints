# Tech Stack Decisions — Complaint Resolution System

This document captures **every** tech choice across the three environments (Dev / Test / Prod), with rationale and cost.

---

## Guiding Principles

1. **Cost-effective first** — Use free tiers wherever possible during dev & test.
2. **Production-grade later** — Move to managed GCP services only in prod.
3. **Same codebase, different config** — Spring profiles handle env-specific wiring.
4. **GCP-centric** — Single cloud, single console, simpler ops.

---

## Application Stack (same across all environments)

| Layer | Tool | Version |
|-------|------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 4.1.0 |
| Security | Spring Security + JWT (jjwt) | latest |
| ORM | Spring Data JPA + Hibernate | bundled |
| DB Migrations | Flyway | latest |
| In-process Cache | Caffeine | latest |
| Rate Limiting | Bucket4j (in-memory) | latest |
| API Docs | springdoc-openapi | 2.x |
| DTO Mapping | MapStruct | 1.5.x |
| Boilerplate | Lombok | latest |
| Build | Maven | 3.9+ |
| Testing | JUnit 5, Mockito, Testcontainers | bundled |
| E2E Testing | Selenium | latest |
| Containerization | Docker | latest |

---

## DEV Environment — Local ($0/month)

**Goal:** Single `docker-compose up` brings up everything for local dev.

| Layer | Choice | Notes |
|-------|--------|-------|
| Compute | Docker Compose on laptop | Single command spins up all services |
| Database | **PostgreSQL** container | Port 5432, mounted volume for persistence |
| Cache | **Caffeine** (in-JVM) | No container needed; zero infra |
| File Storage | **Local `/uploads` folder** | Mapped Docker volume |
| Message Queue | **Spring Events** (in-memory) | No external dependency |
| SMS | **Console log mock** | OTP printed to logs, no real SMS |
| Push Notifications | **Mock** | Logged, not sent |
| Monitoring | **Prometheus + Grafana** containers | Optional, brought up only when needed |
| API Docs | **Swagger UI (springdoc)** | http://localhost:8080/swagger-ui |

**Spring profile:** `dev`

---

## TEST Environment — Cloud-hosted (~$15/month)

**Goal:** Single accessible URL for QA / stakeholders / demos. Uses free tiers wherever possible.

| Layer | Choice | Cost | Notes |
|-------|--------|------|-------|
| Compute | **1× GCP `e2-small` VM** (2 vCPU, 2 GB RAM) | ~$15/month | Runs Spring Boot JAR + Docker for DB |
| Database | **PostgreSQL** (Docker on same VM) | $0 | Or use Supabase free tier (500 MB) as backup |
| Cache | **Caffeine** (in-JVM) | $0 | No external cache; single-pod scale |
| File Storage | **GCP Cloud Storage** | $0 | 5 GB free tier |
| Message Queue | **GCP Pub/Sub** | $0 | 10 GB/month free |
| SMS | **MSG91 sandbox** | $0 | Test OTPs, not delivered to real phones |
| Push Notifications | **Firebase Cloud Messaging** | $0 | FCM free for all volumes |
| Monitoring | **GCP Cloud Logging** | $0 | Auto-collects from VM |
| CI/CD | **GitHub Actions → SSH deploy** | $0 | 2,000 min/month free |
| Domain | **Free subdomain** (`nip.io` or VM IP) | $0 | No custom domain in test |

**Total:** ~$15/month (just the VM). With GCP $300 free credits → $0 for first 90 days.

**Spring profile:** `test`

---

## PROD Environment — Future (~$700-1,100/month)

**Goal:** Production-grade, scalable, HA, managed services.

| Layer | Choice | Approx. Cost | Why |
|-------|--------|--------------|-----|
| Cloud | **GCP — Mumbai `asia-south1`** | — | India region, low latency for Maharashtra |
| Compute | **GKE Autopilot** | $150-250/month | Pay-per-pod, Google manages nodes, auto-scaling |
| Database | **Cloud SQL PostgreSQL (HA)** | $100-200/month | Managed, auto-backups, regional HA |
| Cache | **Caffeine (in-JVM)** — *Memorystore Redis deferred* | $0 (Memorystore: +$35/month when needed) | Add Memorystore only when scaling beyond 1 pod or hitting DB load |
| Object Storage | **GCP Cloud Storage** | $5-10/month | Standard storage tier |
| Message Queue | **GCP Pub/Sub** | $5/month | Serverless, scales infinitely |
| SMS | **MSG91 (DLT-registered)** | ₹0.15/SMS × 10K = ~$600/month | Indian SMS gateway, govt-friendly |
| Push Notifications | **Firebase Cloud Messaging** | $0 | Free unlimited |
| Monitoring | **GCP Cloud Ops** (Logging + Monitoring + Dashboards) | $0-20/month | Free 50 GB logs/month |
| CI/CD | **GitHub Actions → GKE** | $0-30/month | 2,000 min free, $0.008/min after |
| Secrets | **GCP Secret Manager** | $0.06/secret/month | Manage JWT keys, DB passwords, API keys |
| Domain + SSL | Custom domain + Google-managed SSL | $10-15/month | Required for prod |

**Total estimate:** ~$700-1,100/month (₹58K-92K)

**Spring profile:** `prod`

---

## Spring Profiles Strategy

| Profile | Activated By | DB | Cache | Storage | Queue | SMS |
|---------|-------------|----|----|----|----|----|
| `dev` | `--spring.profiles.active=dev` | Local PG (Docker) | Caffeine (in-JVM) | Local FS | Spring Events | Console mock |
| `test` | `--spring.profiles.active=test` | PG on VM | Caffeine (in-JVM) | GCS | Pub/Sub | MSG91 sandbox |
| `prod` | `--spring.profiles.active=prod` | Cloud SQL | Caffeine (Memorystore later) | GCS | Pub/Sub | MSG91 prod |

Codebase is identical across profiles. Only `application-{profile}.yml` differs.

---

## Why These Choices?

### Why GCP (not AWS/Azure)?
- **GKE Autopilot** — best-in-class managed Kubernetes, pay-per-pod (no node fees).
- **Mumbai region** — low latency for Maharashtra users.
- **Generous free tier** — $300 credits + always-free services.
- **Simple billing** — single project, single invoice.

### Why PostgreSQL?
- Open-source, mature, ACID-compliant.
- Excellent JSON support (for storing complaint metadata).
- Managed Cloud SQL option in prod.
- Strong community + Spring Data JPA integration.

### Why no Redis in v1?
- Originally considered for OTP storage, refresh-token store, rate limiting, hot caching.
- At v1 scale (single pod, ~10K complaints/day) **all of these are handled by Postgres + Caffeine (in-JVM cache) + Bucket4j (in-memory rate limit)**.
- Adding Redis = extra moving part, extra cost, extra ops — no functional gain.
- **Trigger to add Redis (Memorystore in prod):** scaling to 2+ pods, OTP volume > 100K/day, or measurable DB hot-spotting on cache reads.

### Why MSG91?
- Indian company, **DLT-compliant** (mandatory for SMS in India).
- Cheaper than Twilio (~₹0.15 vs ₹0.40 per SMS).
- Sandbox available for free testing.

### Why Firebase Cloud Messaging (FCM)?
- Industry standard for push notifications on iOS + Android.
- **Free** for unlimited messages.
- Works seamlessly with React Native.

### Why GKE Autopilot (not standard GKE)?
- No node management — Google handles capacity, scaling, patching.
- Pay only for pods you run.
- Lower ops overhead for a small team.

### Why GitHub Actions?
- Tight integration with GitHub source code.
- 2,000 free min/month for private repos.
- Massive marketplace of pre-built actions for GCP, Docker, K8s.

---

## Tools NOT chosen (and why)

| Considered | Rejected Because |
|------------|------------------|
| AWS RDS | GCP Cloud SQL is sufficient and keeps us in one cloud |
| Twilio SMS | 2-3× more expensive than MSG91 for India |
| Kafka | Pub/Sub is serverless and cheaper at our volume |
| ELK Stack | Heavy to manage; GCP Cloud Logging is free + zero setup |
| Standard GKE | More expensive due to node management overhead |
| MongoDB | Our data is relational (consumers, complaints, substations) |
| Self-hosted Postgres in prod | Backups, HA, patching = ops burden; Cloud SQL is better |

