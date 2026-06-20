# AGENTS.md

> Pointer file for AI agents working in this repo (GitHub Copilot, Claude, Cursor, Codex CLI, Devin, etc.).
> Humans: see [`docs/README.md`](docs/README.md).

## TL;DR

- **Spring Boot 4.1.0 · Java 21 · Maven (single-module)** backend for a Complaint Resolution System (Maharashtra State Electricity Board).
- Sibling repo `complaints-frontend/` holds the React + Expo frontend.
- The detailed conventions you must follow are in **[`.github/copilot-instructions.md`](.github/copilot-instructions.md) — read that file first**, then come back here.

## Where to find what

| You need to know… | File |
|-------------------|------|
| Code conventions, hard rules, test policy | [`.github/copilot-instructions.md`](.github/copilot-instructions.md) |
| Business rules / user roles | [`docs/BRD.md`](docs/BRD.md) |
| Architecture / API contracts / packages | [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md) |
| **What has actually shipped + incidents per stage** | [`docs/IMPLEMENTATION_LOG.md`](docs/IMPLEMENTATION_LOG.md) |
| Authoritative DB schema | [`docs/schema.sql`](docs/schema.sql) |
| Frontend contract | [`docs/FRONTEND_DESIGN.md`](docs/FRONTEND_DESIGN.md) |
| Tech stack choices + Maven layout | [`docs/TECH_STACK.md`](docs/TECH_STACK.md) |
| How to run locally / deploy | [`docs/ENVIRONMENT_SETUP.md`](docs/ENVIRONMENT_SETUP.md) |
| How to contribute (branches, commits, tests) | [`CONTRIBUTING.md`](CONTRIBUTING.md) |

## Run locally (one-liner orientation)

```bash
docker compose up -d                      # Postgres
export BOOTSTRAP_ADMIN_EMPLOYEE_ID=ADMIN001
export BOOTSTRAP_ADMIN_PASSWORD='ChangeMe!123'
export BOOTSTRAP_ADMIN_SUBDIVISION_CODE=SUB-NSK-001
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# → http://localhost:8080/swagger-ui.html
```

## Five rules you must not break

1. **Timezone `Asia/Kolkata` (IST)** for all business calculations.
2. **Records for DTOs, Lombok for entities, hand-written mappers** (no MapStruct).
3. **Business errors → `BusinessException(ErrorCode.*)`.** Add to the `ErrorCode` enum first.
4. **Flyway migrations are immutable** — never edit a committed `V<x.y>__…sql`; add a new one.
5. **Minimum tests.** 1 happy + 1 unhappy path is the bar. No exhaustive matrices. See `.github/copilot-instructions.md → Minimum-test policy`.

## Three design rules (do not over-engineer)

1. **SOLID applied proportionally** — not academically. One service per business capability, not 1500-line god classes; but also no 4-layer ceremony for a 50-line CRUD.
2. **Patterns only when they earn their keep** — Strategy / Factory / Events / Specification are welcome where there are real alternatives or real branching policies. Don't add an interface that has exactly one implementation. Don't add an abstract base for fewer than two subclasses.
3. **Add the abstraction the *second* time you need it, not the first.** Refactoring towards a pattern is cheap; refactoring out of speculative abstractions is expensive.

Full guidance: `.github/copilot-instructions.md → "Design principles — SOLID, but don't over-engineer"`.

## When opening a PR

Use `.github/PULL_REQUEST_TEMPLATE.md`'s checklist. CI is the gate; expect it to enforce ArchUnit boundaries + tests + lint.

