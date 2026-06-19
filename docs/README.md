# Complaint Resolution System — Documentation

Documentation index for the **Complaint Resolution System** (State Electricity Board, Maharashtra).

| Document | Description |
|----------|-------------|
| [BRD.md](./BRD.md) | Business Requirements Document — what we are building and why |
| [TECH_STACK.md](./TECH_STACK.md) | Tech stack decisions per environment (Dev / Test / Prod) with cost analysis |
| [TECHNICAL_DESIGN.md](./TECHNICAL_DESIGN.md) | System architecture, package structure, DB schema, API contracts, security |
| [ENVIRONMENT_SETUP.md](./ENVIRONMENT_SETUP.md) | How to run Dev (local), deploy to Test (GCP VM), and plan for Prod (GKE) |
| [schema.sql](./schema.sql) | Full PostgreSQL DDL for all tables |

---

## Quick Links

- **Project goal:** Help consumers raise complaints (power outage, low voltage, transformer fault, etc.) against their consumer number and track resolution.
- **Scale target:** 1M consumers, ~10K complaints/day across Maharashtra.
- **Tech:** Java 21 · Spring Boot 4.1 · PostgreSQL · Caffeine (in-JVM cache) · GCP · Kubernetes (GKE Autopilot in prod)
- **Clients:** Web portal (consumers + admins + engineers), React Native mobile app (technicians + engineers). Consumers also use the web portal / app via a public "Consumer" entry point — **no consumer login or registration**.

## Roles & Onboarding

- **Consumer** — **no registration, no login, no password.** Each time they submit a complaint, view complaints, cancel, or leave feedback they enter **Consumer ID + Mobile + OTP** to get a 5-minute verification token.
- **Admin** — seeded directly in the DB. First admin via the bootstrap runner (`BOOTSTRAP_ADMIN_EMPLOYEE_ID` / `BOOTSTRAP_ADMIN_PASSWORD` / `BOOTSTRAP_ADMIN_SUBDIVISION_CODE` env vars); additional admins via DBA SQL insert. **One active admin per Subdivision.** No admin self-registration API.
- **Engineer** — created by an **Admin** only (scoped to a DC within the admin's subdivision). **One active engineer per DC.**
- **Technician** — created by an **Admin** or an **Engineer** (engineers can only create technicians within their own DC). **Many technicians per DC.**
- **Staff login:** Employee ID + Password. New staff accounts (and the bootstrap admin) are created with `password_reset_required = true`, so the **first login forces a password change** before anything else is allowed.

## Project Phases

1. **Phase 1 (Dev)** — Build locally with Docker Compose. Zero infra cost.
2. **Phase 2 (Test)** — Deploy to a single GCP VM for QA/UAT. ~$15/month.
3. **Phase 3 (Prod)** — GKE Autopilot + Cloud SQL + managed services. ~$700-1,100/month.

