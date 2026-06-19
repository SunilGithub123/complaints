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
- **Clients:** Web portal (consumers + admins), React Native mobile app (field technicians + engineers).

## Project Phases

1. **Phase 1 (Dev)** — Build locally with Docker Compose. Zero infra cost.
2. **Phase 2 (Test)** — Deploy to a single GCP VM for QA/UAT. ~$15/month.
3. **Phase 3 (Prod)** — GKE Autopilot + Cloud SQL + managed services. ~$700-1,100/month.

