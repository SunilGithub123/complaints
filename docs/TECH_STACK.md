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
| DTO Mapping | Hand-written `*Mapper` classes (no MapStruct — full control) | — |
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
| MongoDB | Our data is relational (consumers, complaints, subdivisions, DCs) |
| Self-hosted Postgres in prod | Backups, HA, patching = ops burden; Cloud SQL is better |
| MapStruct | Adds an annotation-processor + magic; hand-written `*Mapper` classes give us full control and zero build-time surprises at our scale |
| **Multi-module Maven layout (v1)** | A single-module Maven project with strong package boundaries is enough for the v1 monolith. Multi-module adds POM overhead and friction without giving us anything we can't already get from package discipline. We can extract modules later if/when a piece is genuinely reused or split into its own service. See **Build Layout** below. |

---

## Build Layout — Single-Module Maven (v1)

We are using a **single-module Maven project** for v1 — one `pom.xml` at the project root, all code under `src/main/java/com/example/complaints/...` split into the packages listed in `TECHNICAL_DESIGN.md §3` (`auth`, `complaint`, `masterdata`, `notification`, `storage`, `audit`, `datasync`, `common`, `config`).

### Why single-module is the right choice now

| Factor | Single-module | Multi-module |
|--------|---------------|--------------|
| **App count** | 1 deployable JAR. Multi-module shines when you have ≥ 2 deployables (e.g., `app` + `batch-job` + `cli`). | Overkill for one JAR. |
| **Team size** | Small team (1–3 devs). Module boundaries cost more in POM ceremony than they save. | Pays off with ≥ 5–10 devs working in parallel on separate modules. |
| **Build time** | One `mvn package` (~30s clean for v1). | Faster *incremental* builds for huge codebases, but adds reactor / inter-module-version overhead. |
| **Dependency direction enforcement** | Enforced by **ArchUnit** tests + package-private classes. | Enforced by Maven (compile error if a module imports the wrong way). Stronger, but ArchUnit is good enough. |
| **IDE experience** | Trivial — open one project. | Slightly more steps; multi-module Maven import in IntelliJ is reliable but noisier. |
| **Refactor cost** | Renaming a package = single IDE refactor. | Cross-module moves require POM edits. |
| **CI complexity** | One job, one cache. | Multi-module reactor + per-module test runs add wiring. |
| **Spring Boot conventions** | Default; every Spring Initializr project starts this way. | Common only for "platform" projects with shared starters. |

### How we keep module discipline *without* multi-module

1. **Package boundary tests with ArchUnit** — a small set of JUnit tests under `src/test/java/com/example/complaints/architecture/` that fail the build if, e.g., `notification` imports anything from `complaint.service` directly (must go through events), or if any module imports from `controller` of another module.
2. **Package-private by default** — public classes only when crossing a module boundary (DTOs, service interfaces, events). Implementations stay package-private.
3. **Module-local DTOs** — no leaking JPA entities across modules; cross-module communication is through DTOs / events only.
4. **Single shared `common` package** for cross-cutting utilities, exception classes, and the `ApiResponse<T>` envelope.

### When (and how) we would split into multi-module

We'd revisit *only* if one of these triggers:

- A second deployable appears (e.g., a standalone `datasync-worker` JAR, or a `notification-worker` once Pub/Sub goes async-only).
- The team grows past ~5 backend devs and merge contention becomes painful.
- We want to publish a reusable library (e.g., `complaints-client-sdk` for the EB system to call us).

**Migration path** when it happens (mechanical, ~1 day):

```
complaints/                                    complaints/
├── pom.xml          (single)         →        ├── pom.xml                 (parent, packaging=pom)
├── src/main/java/com/example/...              ├── complaints-common/      (shared DTOs, enums)
                                               ├── complaints-core/        (domain modules)
                                               ├── complaints-app/         (Spring Boot app — depends on core)
                                               └── complaints-datasync/    (new deployable, optional)
```

Because the v1 packages already mirror the eventual modules 1:1, the split is a `git mv` + 4 new `pom.xml` files — no code changes.

### Verdict

**Stay single-module for v1 → v2.** Re-evaluate at v3 or when a second deployable is genuinely needed.
