# Copilot / AI Assistant Instructions — `complaints` (backend)

> Read this first. The detailed designs live in [`docs/`](../docs/README.md). When in doubt, those docs are the source of truth.

## Project at a glance

- **Complaint Resolution System** for the Maharashtra State Electricity Board.
- **Spring Boot 4.1.0 · Java 21 · Maven (single-module)** monolith.
- PostgreSQL · Flyway · Caffeine (in-JVM cache) · Bucket4j (in-memory rate limit) · JJWT · GCP (test/prod).
- Sibling repo `complaints-frontend/` holds the React + Expo apps. Backend is **API-only**.

## Hard rules (never violate)

1. **Timezone is `Asia/Kolkata` (IST) everywhere.** Persistence uses `TIMESTAMPTZ` (UTC on disk) but all business calculations (SLA deadlines, the `<YYYY><MM>` portion of ticket numbers, scheduler cron expressions, "today's complaints" filters) are computed in IST. Schedulers use `zone = "Asia/Kolkata"`.
2. **DTOs are Java 21 `record` types.** Entities use Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`).
3. **Hand-written `*Mapper` classes** for entity ↔ DTO. **No MapStruct.**
4. **Every business error throws `BusinessException(ErrorCode, args…)`.** The `ErrorCode` enum in `common.exception.ErrorCode` is the single catalogue. Add an entry there before throwing a new code. Never throw raw `RuntimeException` from services.
5. **Flyway migrations are immutable once committed.** Never edit a `V<x.y>__…sql` file that has been merged — add a new `V<x.y+1>__…sql` instead. Naming: `V<major>.<minor>__<snake_case>.sql`.
6. **No consumer login.** Consumers are verified per-action via Consumer ID + Mobile + OTP → a 5-min, non-refreshable consumer verification JWT. Consumers have **no row** in `user_account`.
7. **Staff login identifier is `employee_id`** (not email). All new staff accounts (including the bootstrap admin) are created with `password_reset_required = true` and must change password on first login before any other endpoint accepts their token.
8. **Scope filters apply automatically in repositories** based on the caller's role: `TECHNICIAN → assigned_technician_id = me`, `ENGINEER → distribution_center_id = my_dc`, `ADMIN → subdivision_id = my_subdivision`.
9. **One active admin per subdivision, one active engineer per DC.** Enforced by partial-unique indexes and by the service layer. Many technicians per DC.
10. **No `@Transactional` on controllers.** Always on the service method.
11. **Never serialize JPA entities directly to HTTP.** Always go through a DTO + mapper.
12. **`spring.jpa.open-in-view: false`** — never rely on lazy loading outside a transaction.

## Conventions

### Packages (single-module Maven; see `TECHNICAL_DESIGN.md §3`)

```
com.example.complaints
├── auth · complaint · masterdata · notification · storage · audit · datasync · consumer
├── common         (ApiResponse, PageResponse, ErrorCode, BusinessException, GlobalExceptionHandler, utilities)
└── config         (SecurityConfig, JwtConfig, OpenApiConfig, WebConfig, CaffeineCacheConfig, StorageConfig)
```

Cross-package rules (enforced by ArchUnit tests under `src/test/java/.../architecture/`):
- A module's `controller` may call its own `service` only.
- A module's `service` may call its own `repository` and other modules' `service` interfaces, but **never** another module's `repository` or `controller`.
- Cross-module data exchange is via DTOs / records / events — **never** JPA entities.

### REST APIs

- Base URL `/api/v1`. Additive changes stay on v1; breaking changes bump to v2.
- All responses wrapped in `ApiResponse<T>` = `{ success, data, error, timestamp }`.
- Pagination defaults: `?page=0&size=20&sort=createdAt,desc`. Hard cap `size=100`. Wrapped in `PageResponse<T>`.
- Endpoints under `/consumer/**` are gated by `ConsumerVerificationFilter` (5-min token, not the JWT filter).
- Endpoints under `/staff/**`, `/admin/**`, `/engineer/**`, `/technician/**` are gated by `JwtAuthFilter` + `PasswordResetRequiredFilter`.

### Naming conventions

**Files & types**

| Kind | Convention | Example |
|------|------------|---------|
| Class / interface / enum / record | `PascalCase`, noun phrase | `ComplaintAssignmentService`, `UserRole`, `LoginRequest` |
| Class suffixes — **required** when the type fits the role | `Controller`, `Service`, `Repository`, `Mapper`, `Filter`, `Config`, `Properties`, `Factory`, `Validator`, `Listener`, `Runner` | `JwtFactory`, `SecurityConfig`, `JwtProperties` |
| DTO suffixes — **required** | `Request`, `Response`, `Summary`, `View` | `ChangePasswordRequest`, `StaffSummaryResponse` |
| Event / Exception suffixes — **required** | `Event`, `Exception` | `ComplaintAssignedEvent`, `BusinessException` |
| Test classes | unit → `*Test`; integration (Docker / Testcontainers) → `*IT` | `StaffAuthServiceTest`, `ComplaintsApplicationIT` |
| Flyway migrations | `V<major>.<minor>__<snake_case>.sql`; dev-only seed migrations start at `V1000.x` | `V1.3__add_updated_at_trigger.sql`, `V1000.0__seed_dev_data.sql` |

**Members**

| Kind | Convention | Example |
|------|------------|---------|
| Method | `camelCase`, **imperative verb** (action) for commands; `findXxx` / `getXxx` for reads; **no** `doXxx`, `handleXxx`, `processXxx`, `executeXxx`, `manageXxx` | `assignToTechnician`, `markBreached`, `findByEmployeeId` |
| Boolean methods / fields | `is…`, `has…`, `can…`, `should…` | `isActive`, `hasOpenComplaints`, `passwordResetRequired` |
| Field / local / parameter | `camelCase` | `subdivisionId`, `accessToken` |
| Constant (`static final`, enum value) | `SCREAMING_SNAKE_CASE` | `DEFAULT_PAGE_SIZE`, `ADMIN`, `IST_TIMEZONE` |
| Generic type parameter | single uppercase letter or `PascalCase` ending in `T` | `T`, `K`, `EventT` |

**Packages**

- All lowercase, **no underscores**, **no camelCase**.
- One module per top-level package under `com.example.complaints.<module>`; sub-packages are role-based (`controller`, `service`, `repository`, `model`, `dto`, `mapper`, `event`).
- Avoid `util` / `helper` / `common` *inside* a module — those names are reserved for the shared `com.example.complaints.common` package.

**REST URLs**

- `kebab-case` segments, plural resource nouns: `/api/v1/admin/masterdata/distribution-centers/{id}/activate`.
- Path parameter names match the segment: `{id}`, `{subdivisionId}`, `{complaintNumber}`.
- Query parameters in `camelCase`: `?subdivisionId=…&page=0&size=20`.
- Verbs in URLs only for non-CRUD state transitions: `/activate`, `/deactivate`, `/assign`, `/resolve`, `/cancel`. **Never** `/get-…`, `/list-…`, `/create-…`.

**Database**

- Table & column names in `snake_case`, singular table names: `user_account`, `complaint_category`, `distribution_center`.
- PKs are `id BIGINT`; FKs are `<referenced_table>_id` (e.g. `subdivision_id`).
- Boolean columns prefixed `is_` only when needed for disambiguation (`enabled`, `revoked` are fine; `is_active` vs `active` — pick one per table and stay consistent).
- Indexes: `ix_<table>_<col(s)>`; unique: `ux_<table>_<col(s)>`; partial unique: `ux_<table>_<col>_<predicate_short>`.
- FK constraint: `fk_<table>__<referenced_table>` (double underscore).

### Code style

**Java / Spring conventions enforced in review**

1. **Constructor injection only** — `final` fields + Lombok `@RequiredArgsConstructor` on services/controllers/components. No field injection (`@Autowired` on fields), no setter injection.
2. **Records are canonical** for DTOs, event payloads, value objects. **Do not** add Lombok to records — they already have a canonical constructor + `equals`/`hashCode`.
3. **Validation lives on request records.** Use `jakarta.validation` annotations (`@NotBlank`, `@Size`, `@Pattern`, `@Min`, `@Max`, `@Email`) on record components. Controllers add `@Valid`; the `GlobalExceptionHandler` turns failures into `VALIDATION_FAILED`.
4. **Time types:** prefer `java.time.Instant` for storage (`TIMESTAMPTZ`) and `OffsetDateTime` in IST for wire format. Convert via `common.util.DateUtils.toIst(...)` in mappers. **Never `java.util.Date`**, never `LocalDateTime` for stored values.
5. **`Optional` only as a return type** — not as a field, not as a method parameter, not in collections.
6. **Streams** — fine, but don't chain more than ~5 operations; extract intent into a named method. No `Collectors.toList()` — use `toList()`.
7. **Exceptions** — services throw `BusinessException(ErrorCode.*)`. **Never** `RuntimeException`, **never** `IllegalStateException` for business rules. Catch the narrowest type; **never** `catch (Exception e)` outside `GlobalExceptionHandler`.
8. **Logging** — SLF4J via Lombok `@Slf4j`. Use parameterised messages: `log.info("Assigned complaint {} to technician {}", complaintId, technicianId);`. **Never** log secrets, OTPs, passwords, JWTs, refresh tokens, or full request bodies. `log.debug` is fine for development context; remove `log.info("...");` left over from debugging.
9. **Null handling** — fields are non-null unless the DB column is nullable. Document nullability via `@Nullable` from Spring (`org.springframework.lang.Nullable`) on the rare return type. Don't sprinkle `Objects.requireNonNull` in services; rely on validation + DB constraints.
10. **Magic numbers / strings** — extract to a `private static final` constant or an enum the first time they appear in business logic. Token TTLs, rate-limit bucket sizes, page-size caps, etc.
11. **`@Transactional`** — on the service method, never on a controller, repository, or `@Configuration`. Read-only methods: `@Transactional(readOnly = true)`. Don't span more than one external call (HTTP / SMS / push) inside a transaction — extract those to after-commit hooks or events.
12. **Method length** — aim ≤ 30 lines. If a service method grows past that, the bodies of its branches usually want to be `private` named methods inside the same service.
13. **Class length** — aim ≤ 300 lines. If a service exceeds that, it's probably doing two things; split per business capability (`ComplaintAssignmentService` vs `ComplaintResolutionService`).
14. **Imports** — no wildcard imports (`import a.b.*`). Order: `java.*`, `javax.* / jakarta.*`, third-party, project. The IDE handles this; don't fight it.
15. **Comments** — explain *why*, not *what*. Javadoc on public service methods + REST endpoints; `// inline` only when the reason isn't obvious from the code. Delete commented-out code.
16. **TODO** — only with an owner + ticket reference: `// TODO(sunil, #123): switch to async once notification module lands`. No anonymous TODOs.

**Spring-specific style**

- One bean per `@Configuration` `@Bean` method; no factory methods returning multiple beans.
- Properties classes are `record`s annotated `@ConfigurationProperties(prefix = "…")` and registered via `@ConfigurationPropertiesScan` (not `@EnableConfigurationProperties` per class).
- `@Cacheable` / `@CacheEvict` go on the service method; cache names declared once in `CaffeineCacheConfig`. No string-literal cache names sprinkled in services — use a constant.
- Scheduled jobs: `@Scheduled(cron = "...", zone = "Asia/Kolkata")`. Always specify the zone explicitly.
- Tests: `@DisplayName` for human-readable test names. Prefer slice tests (`@WebMvcTest`, `@DataJpaTest`) over full `@SpringBootTest`. Mock with Mockito `@MockitoBean` (SB 4.1) — not the deprecated `@MockBean`.

**Formatting**

- 4-space indent, no tabs. Soft line wrap at ~120 chars (don't manually wrap fluent builder chains tighter than that).
- Braces always — no single-line `if (x) return;` without braces.
- One blank line between methods, none between consecutive `private static final` constants.

## Design principles — SOLID, but don't over-engineer

Apply SOLID **proportionally** to the size of the change. The goal is "easy to read, easy to change", not "academically pure".

### SOLID — what it actually means here

| Principle | What it looks like in this codebase | Anti-pattern to avoid |
|-----------|--------------------------------------|------------------------|
| **S — Single Responsibility** | One service per business capability (`ComplaintAssignmentService`, `ComplaintResolutionService`, `ComplaintCancellationService`). One mapper per entity. One repository per aggregate root. | A 1500-line `ComplaintService` doing assign + resolve + cancel + cache + audit + email. |
| **O — Open / Closed** | New complaint category? Just add a row in `complaint_category`. New status transition? Add to `ComplaintStatus` enum + the validator's allow-table. | Editing `if/else if/else` ladders that fan out across the codebase every time a status is added. |
| **L — Liskov** | If we publish a `StorageService` interface, `LocalStorageService` and `GcsStorageService` honour the same contract — same exceptions, same return shapes. | A subclass that throws `UnsupportedOperationException` from a method on the parent. |
| **I — Interface Segregation** | Small, intention-revealing interfaces (`OtpSender`, `OtpVerifier`) over fat ones. | One `AuthFacade` with 30 methods that every caller depends on. |
| **D — Dependency Inversion** | Services depend on **interfaces** for things that have multiple implementations or external side effects (`StorageService`, `SmsService`, `EbSystemClient`). For pure CRUD JPA repos, depending directly on the Spring Data interface is fine — adding an extra interface in front buys nothing. | Mocking out a JPA repository through a hand-rolled `IComplaintRepository` interface that has zero alternative implementations. |

### Design patterns — use only when they earn their keep

**Use a pattern when** there's a real second-or-third implementation in flight, a real branching policy, or a real sequencing concern. **Skip the pattern when** the alternative is one method call.

| Pattern | Use when… | Don't use when… |
|---------|-----------|-----------------|
| **Strategy** | `StorageService` (local FS vs GCS), `SmsService` (console mock vs MSG91), `PushService` (mock vs FCM) — switched by Spring profile via `@Profile` or `@ConditionalOnProperty`. | There's only ever going to be one implementation. |
| **Factory** | OTP / verification / staff-access / staff-refresh JWT issuers all share signing, but differ in claims + TTL — use a `JwtFactory` with explicit per-purpose builders. | Constructing a plain DTO. Use `new` or a record's canonical constructor. |
| **Domain Events** | `ComplaintAssignedEvent`, `SlaBreachedEvent`, `ComplaintStatusChangedEvent` — published from services, consumed by `notification` + `audit` listeners. Decouples cross-module side effects. | Calling another service's method directly within the same module. |
| **State / Status validator** | `ComplaintStatus` transitions (`SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` + alt terminals) — encode the allow-table once in `ComplaintStatusTransition` and reuse it. | A 3-state lifecycle. A `switch` is fine. |
| **Specification (Spring Data)** | Building dynamic complaint search filters (`status × severity × DC × technician × dateRange × search`). | Single-criterion finders — use a derived `findByX` method. |
| **Builder (Lombok `@Builder`)** | JPA entities with many optional fields. | Records — they have a canonical constructor; chaining adds noise. |
| **Decorator** | Cross-cutting behaviour like rate-limiting, audit logging, caching — usually delivered via Spring AOP / `@Cacheable` / `@Transactional`, not hand-rolled wrappers. | Wrapping a single class to add a single method. Just add the method. |

### Over-engineering smells — reject in code review

- A new interface with **exactly one production implementation** that is not a real strategy / not mocked in tests with a meaningful alternative.
- An **abstract base class** below 2 concrete subclasses.
- A **DTO that mirrors the entity 1:1** with no field changes — at that point the entity should not have been exposed (use `@Query` with a record projection instead).
- A **dispatcher / router class** that wraps a single `if/else`.
- **Generic types on services** (`ComplaintService<T extends Complaint>`) — we have one complaint type.
- **Hand-rolled "framework" code** — Spring already handles transactions, validation, security, caching, scheduling, events. Use them.
- **Two-level service layering** ("`ComplaintService` calls `ComplaintBusinessService` calls `ComplaintCoreService`") with no behaviour added at any layer.
- **Premature async** — synchronous code is fine until profiling says otherwise.
- **Premature optimization** — readable code first; benchmark, then optimize.

### Rule of thumb

> **Add an abstraction the second time you genuinely need it, not the first.**
>
> *(One implementation = `class`. Two implementations = `interface` + two classes. Three implementations = consider a registry / strategy map.)*

If you cannot point to a concrete, current need for an extra layer / interface / pattern, don't add it. We can always introduce it later — refactoring towards a pattern is cheap; refactoring out of speculative abstractions is expensive.

## Minimum-test policy (very important — keep tests lean)

See `TECHNICAL_DESIGN.md §14.2`. Summary:

- **Per service method:** 1 happy path + 1 failure / edge case.
- **Per controller endpoint:** 1 MockMvc test covering status + response shape + auth/role enforcement.
- **Per custom repository query:** 1 Testcontainers integration test.
- **Per Flyway migration:** verified by the schema boot — no extra test unless it contains data logic.
- **No** exhaustive parameterised matrices, **no** snapshot tests, **no** "renders without crashing" tests, **no** tests that mock the system under test.
- **No hard coverage gate in v1.** Aim for high-signal, not high-percentage.

> **Rule of thumb:** *"Would I miss this if it broke in prod tomorrow?"* If yes → write the test. Otherwise → skip.

## When generating code

- Read the **relevant section of `docs/TECHNICAL_DESIGN.md`** (and `BRD.md` for business rules) **before** producing code. Cite the section in the PR description.
- Prefer **constructor injection** (`final` fields + `@RequiredArgsConstructor`) over field injection.
- Validation annotations (`@NotBlank`, `@Size`, `@Pattern`, etc.) belong on the **record components** of request DTOs.
- New error scenario? **Add an `ErrorCode` enum entry first**, then throw `BusinessException(ErrorCode.NEW_CODE, args…)`.
- New schema change? **New Flyway migration file** with the next `V<x.y+1>__…sql` number — never modify the existing ones.
- New endpoint? **Update OpenAPI / springdoc annotations** so the FE codegen sees it.

## When suggesting tests

- One test class per service / controller.
- Use `@DisplayName` for human-readable test names.
- Integration tests live next to the production class, suffix `IT`, run via Failsafe (not Surefire).
- Use `@SpringBootTest` only when necessary; prefer slice tests (`@WebMvcTest`, `@DataJpaTest`).
- **Stop at the minimum** — do not generate exhaustive parameterised variants unless explicitly asked.

## What NOT to do

- Don't add MapStruct, ModelMapper, Jackson mixins for entity↔DTO, or any reflection-based mapper.
- Don't add Lombok to records.
- Don't catch `Exception` broadly. Catch specific exceptions or let `GlobalExceptionHandler` handle them.
- Don't introduce new top-level packages without checking ArchUnit rules first.
- Don't reach for Redis / Kafka / external services — Caffeine + Bucket4j + Postgres are the v1 stack (see `TECH_STACK.md`).
- Don't log secrets, OTPs, passwords, or JWTs. Use structured logging (`log.info("user {} ...", userId)`).
- Don't break the **single-module Maven** layout. If a separation seems needed, file a discussion first (`TECH_STACK.md → Build Layout`).

## Useful pointers

- High-level architecture: [`docs/TECHNICAL_DESIGN.md`](../docs/TECHNICAL_DESIGN.md)
- Business rules: [`docs/BRD.md`](../docs/BRD.md)
- Frontend contract: [`docs/FRONTEND_DESIGN.md`](../docs/FRONTEND_DESIGN.md)
- Schema (authoritative): [`docs/schema.sql`](../docs/schema.sql)
- Tech-stack rationale + Maven layout: [`docs/TECH_STACK.md`](../docs/TECH_STACK.md)
- Local setup: [`docs/ENVIRONMENT_SETUP.md`](../docs/ENVIRONMENT_SETUP.md)

