<!--
PR title format: `<area>: <short imperative summary>`
Examples:  `auth: add staff first-login password change`
           `complaint: add Admin cross-DC reassignment`
           `infra: bump Spring Boot to 4.1.1`
-->

## What this PR does

<!-- 1–3 sentences. Link the issue if any. -->

## Why

<!-- Business reason or design pointer. Reference `docs/...md §X.Y` where relevant. -->

## How

<!-- High-level summary of the implementation. Mention any non-obvious choices. -->

## Checklist

> Tick what applies. Anything skipped should have a one-line justification.

- [ ] Follows [`.github/copilot-instructions.md`](./.github/copilot-instructions.md) (records for DTOs, Lombok for entities, hand-written mappers, no MapStruct).
- [ ] **No speculative abstractions** — every new interface has ≥ 2 real implementations or a real test double; no abstract base for < 2 subclasses; no "manager wrapping a single method". (See *Design principles — SOLID, but don't over-engineer* in the instructions.)
- [ ] All business errors throw `BusinessException(ErrorCode.*)`; new error scenarios have a new `ErrorCode` enum entry.
- [ ] Schema change? → new Flyway file `V<x.y+1>__<snake_case>.sql` (never edits an existing migration).
- [ ] New / changed endpoint? → springdoc OpenAPI annotations updated; `docs/TECHNICAL_DESIGN.md §5` reflects the change.
- [ ] New role / permission rule? → reflected in `SecurityConfig` + `@PreAuthorize` + ArchUnit scope tests.
- [ ] Tests added per the **minimum-test policy** (`TECHNICAL_DESIGN.md §14.2`): **1 happy path + 1 failure path** for the affected service / controller. *No exhaustive matrices.*
- [ ] All datetimes use `Asia/Kolkata` for business calculations; persisted as `TIMESTAMPTZ`.
- [ ] No secrets, OTPs, passwords, or JWTs logged.
- [ ] `./mvnw clean verify` passes locally.
- [ ] `docs/` updated if architecture / API / schema / convention changed.

## Risk & rollback

<!-- What's the blast radius if this is wrong in prod? What's the rollback path? -->

## Screenshots / sample request-response (optional)

<!-- For API changes, paste a sample request + response. For UI-affecting backend changes, link the FE PR. -->

