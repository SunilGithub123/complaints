# Contributing — `complaints` (backend)

Thanks for working on the Complaint Resolution System. This is the short version; the long version lives in [`docs/`](docs/README.md).

## Prerequisites

- Java 21 (`brew install openjdk@21` on macOS; `sdk install java 21-tem` via SDKMAN)
- Maven 3.9+ (`brew install maven`) — or use the wrapper `./mvnw`
- Docker Desktop (for Postgres)
- Git
- An IDE (IntelliJ IDEA recommended; VS Code with the Java extension works too)

## First-time setup

```bash
git clone <repo-url>
cd complaints

# 1. Start Postgres
docker compose up -d

# 2. Set bootstrap admin (only needed on a fresh DB)
export BOOTSTRAP_ADMIN_EMPLOYEE_ID=ADMIN001
export BOOTSTRAP_ADMIN_PASSWORD='ChangeMe!123'
export BOOTSTRAP_ADMIN_SUBDIVISION_CODE=SUB-NSK-001

# 3. Run the app
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 4. Open Swagger
open http://localhost:8080/swagger-ui.html
```

Detailed setup (test/prod envs too) → [`docs/ENVIRONMENT_SETUP.md`](docs/ENVIRONMENT_SETUP.md).

## Day-to-day commands

```bash
./mvnw clean verify             # full build + unit + integration tests
./mvnw test                     # unit tests only
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw spotless:apply           # format code (when Spotless is added)
docker compose down             # stop Postgres (keep data)
docker compose down -v          # stop Postgres + wipe data
```

## Branching & commits

- **Long-lived branches:** `main` (→ prod), `develop` (→ test). Direct pushes to either are forbidden.
- **Feature branches:** `feat/<area>-<short-desc>`, e.g. `feat/auth-first-login-pwd-change`.
- **Bug branches:** `fix/<area>-<short-desc>`.
- **Conventional commits** (relaxed). Format: `<type>(<area>): <imperative summary>`. Types: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`, `build`, `ci`, `perf`.
  - `feat(auth): add /auth/password/change endpoint`
  - `fix(complaint): apply IST when rolling ticket sequence`
  - `chore(deps): bump JJWT to 0.13.0`
- Squash-merge into `develop` / `main`. Keep PRs small and focused.

## PR rules

- Use [`.github/PULL_REQUEST_TEMPLATE.md`](./.github/PULL_REQUEST_TEMPLATE.md). Tick what applies, justify what you skip.
- CI must be green. No "I'll fix it in a follow-up" merges.
- At least 1 reviewer approval (self-approve allowed for solo work, but still open a PR — never push to `develop` directly).

## Code conventions (the short list)

The full list is in [`.github/copilot-instructions.md`](./.github/copilot-instructions.md). The non-negotiables:

1. **Asia/Kolkata (IST)** for all business calculations.
2. **Records for DTOs, Lombok for entities, hand-written mappers.** No MapStruct.
3. **Errors:** `BusinessException(ErrorCode.*)`. Add new codes to the enum.
4. **Flyway migrations are immutable** — add a new `V<x.y+1>__…sql`, never edit a committed one.
5. **Tests:** minimum-test policy from [`docs/TECHNICAL_DESIGN.md §14.2`](docs/TECHNICAL_DESIGN.md) — *1 happy + 1 unhappy per change*. No exhaustive matrices.
6. **`@Transactional` on services, not controllers.** Never serialize entities to HTTP.

## When you change…

| You change… | You also must… |
|-------------|---------------|
| The DB schema | Add a new Flyway file `V<x.y+1>__<snake_case>.sql`; update [`docs/schema.sql`](docs/schema.sql) for reference. |
| An API endpoint | Update springdoc annotations; reflect the change in [`docs/TECHNICAL_DESIGN.md §5`](docs/TECHNICAL_DESIGN.md). |
| A role / permission | Update `SecurityConfig`, `@PreAuthorize`, ArchUnit scope tests, and [`docs/BRD.md §3`](docs/BRD.md) if business-visible. |
| A configuration knob | Add to the relevant `application-<profile>.yml`; document in [`docs/TECHNICAL_DESIGN.md §9`](docs/TECHNICAL_DESIGN.md). |
| A business rule | Update [`docs/BRD.md`](docs/BRD.md) first, then implement. |

## Getting help

- Architecture / design questions → check `docs/` first, then open a discussion.
- Bug / regression → open an issue with reproduction steps.
- Vulnerability → see `SECURITY.md` (TODO before going public).

