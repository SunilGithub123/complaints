# CI / CD Design — backend (`complaints`)

> Standalone design for the GitHub Actions pipeline. Kept out of
> `TECHNICAL_DESIGN.md` so build-pipeline churn doesn't pollute the
> business-domain doc. Living document — append a *Decision Log* entry
> at the bottom when something material changes.
>
> **Scope:** backend only. The sibling `complaints-frontend/` repo has its
> own CI doc (`complaints-frontend/docs/CI_CD_DESIGN.md`) that mirrors this
> file's structure.

---

## 1. Principles

1. **Zero recurring cost in v1.** Every tool listed below has a free tier that
   covers our usage (single-repo, < 5 contributors, < 600 builds/month). No
   paid SaaS, no self-hosted infra.
2. **GitHub-native first.** GitHub Actions, ghcr.io, CodeQL, Dependabot,
   Dependency Review — all baked-in, no extra accounts.
3. **Widely-adopted OSS where GitHub-native isn't enough.** JaCoCo, SpotBugs +
   find-sec-bugs, PMD, Gitleaks, Trivy — each is the de-facto standard in
   its niche, Apache-2.0 / MIT / BSD / LGPL, all in production use at Google /
   AWS / Spring / etc. **Skipped:** SonarCloud (free for *public* repos only;
   not free if `complaints` is private), self-hosted SonarQube (server cost),
   Snyk / Codacy / CircleCI / Codecov private tier (paid).
4. **Fast feedback before strict gates.** PRs get test results + annotations
   inside ~3 min. Optional analyses (CodeQL deep scan, Trivy image scan) run
   asynchronously and report later.
5. **Workflows stay short and orthogonal.** One file per concern; no mega
   pipeline. Easier to skip / retry / read.
6. **Secrets never in YAML.** Repo / org / environment secrets only.
   `GITHUB_TOKEN` for ghcr.io (no PAT needed).
7. **The minimum-test policy still applies.** No coverage % gate; coverage is
   *reported*, not enforced (see `.github/copilot-instructions.md → Minimum-test policy`).

---

## 2. Tool selection (free + market-standard)

| Concern | Tool | License | Why this one |
|---|---|---|---|
| Workflow engine | **GitHub Actions** | Proprietary; free tier | Free unlimited mins on public repos; 2,000 mins/mo on private Free plan. Our `verify` ≈ 2–3 min/run cached → ~600 builds/mo budget on private. |
| Build / test | **Maven Wrapper (`./mvnw verify`)** | Apache-2.0 | Already in repo. Runs Surefire (unit) + Failsafe (IT) + ArchUnit in one shot. |
| JDK | **Eclipse Temurin 21** via `actions/setup-java@v4` | GPLv2+CE | Matches local dev (`java.version=21` in `pom.xml`). |
| Dependency cache | `actions/setup-java`'s built-in `cache: maven` | n/a | Hashes `**/pom.xml`. Cuts warm builds from ~3 min → ~70 s. |
| Integration-test runtime | **Testcontainers + Docker** on `ubuntu-latest` | Apache-2.0 + free runners | Docker pre-installed on GH runners. Already used by `ComplaintsApplicationIT` / `UserAccountRepositoryIT` / `OpenApiExportIT`. |
| Coverage | **JaCoCo Maven plugin** | EPL-2.0 | De-facto JVM coverage standard. Report uploaded as artifact + posted to PR via `Madrapps/jacoco-report` (MIT). **No %-gate.** |
| Static bug analysis | **SpotBugs Maven plugin + find-sec-bugs** | LGPL / Apache-2.0 | Successor to FindBugs; find-sec-bugs adds OWASP-style checks (SQLi, weak crypto). Used by Spring, Apache, etc. |
| Code-smell linter | **PMD Maven plugin** | BSD | Catches Java-21-aware smells SpotBugs misses (long methods, dead code, copy-paste). |
| SAST | **CodeQL** via `github/codeql-action` | Proprietary; free for public + private | GitHub's own. Catches Java CVE patterns. Free on private repos too (unlike SARIF upload from third-party scanners). |
| Dependency CVEs | **GitHub Dependency Review** (`actions/dependency-review-action@v4`) | Proprietary; free | Zero-config. Fails PRs that introduce high-severity vulnerable dependencies. Sources: GitHub Advisory Database + NVD. |
| Dependency updates | **Dependabot** (`.github/dependabot.yml`) | Proprietary; free | Weekly PRs for `maven`, `github-actions`, `docker`. Most-used dep-update tool in the OSS world. |
| Secret scanning | **Gitleaks** (`gitleaks/gitleaks-action@v2`) | MIT | Most-starred OSS secrets scanner; runs offline, no token, low false-positive rate with a small allowlist. |
| Container image scan | **Trivy** (`aquasecurity/trivy-action`) | Apache-2.0 | De-facto OSS image-scanner. Catches base-image + Java-lib CVEs. Used by Google Distroless, Alpine, Docker Inc. |
| Image registry | **ghcr.io** (GitHub Container Registry) | Free | Free for public images; 500 MB free quota for private. Pushed using built-in `GITHUB_TOKEN` — no PAT. |
| Image build | **Spring Boot Docker layered jar** + `docker/build-push-action@v5` + Buildx | Apache-2.0 | We already have a `Dockerfile` (verified). Buildx caches layers across runs. |
| Test-result UI in PR | **`dorny/test-reporter@v2`** | MIT | Most-starred JUnit annotator. Renders pass/fail inline on the PR Files / Checks tab. |

> **Note on SARIF for private repos.** GitHub's "Code Scanning" UI only ingests
> third-party SARIF (SpotBugs / Trivy / PMD) for free on *public* repos. On
> private repos, ingestion requires GitHub Advanced Security (paid). Workaround
> on private: upload SARIF as a workflow artifact + post a human-readable
> summary as a sticky PR comment via
> `marocchino/sticky-pull-request-comment@v2` (MIT). CodeQL is the exception —
> free on private too.

---

## 3. Workflow files (target end-state)

All under `.github/workflows/`. Each file pins action versions by major
(`@v4`), uses `concurrency` to cancel stale runs, and includes
`permissions: { contents: read }` by default — escalated per-job only when
needed (e.g. `packages: write` for ghcr.io push).

| File | Triggers | Avg duration | Required for merge? |
|---|---|---|---|
| `ci.yml` | `pull_request`, `push: main` | ~3 min cached | ✅ |
| `quality.yml` | `pull_request`, `push: main` | ~2 min | ✅ (selected jobs) |
| `codeql.yml` | `pull_request`, `push: main`, `schedule: weekly` | ~4 min | ✅ |
| `image.yml` | `push: main`, `push: tags v*` | ~2 min | ❌ (post-merge only) |
| `deploy-test.yml` | `workflow_dispatch` (manual) | ~1 min | ❌ — **stubbed**, enabled once GCP test env lands (Phase 2 / `ENVIRONMENT_SETUP.md`) |

### 3.1 `ci.yml` — build + test + OpenAPI drift

```
jobs:
  build-test:
    - checkout
    - setup-java (Temurin 21, cache: maven)
    - ./mvnw -B -ntp verify
    - dorny/test-reporter (surefire + failsafe XML)
    - upload-artifact: target/**/site, surefire/failsafe-reports

  openapi-drift:
    needs: build-test
    - checkout
    - git diff --exit-code docs/openapi.json
      # OpenApiExportIT writes the file in build-test; drift = uncommitted spec change
```

**Why two jobs, not steps:** drift check fails *loudly* and independently of
test failures, so reviewers see "spec changed, regenerate FE bindings" without
hunting through a long log. Clears the Stage 3 / Stage 6 follow-up.

### 3.2 `quality.yml` — static analysis + coverage + dependency / secret scan

```
jobs:
  spotbugs:        # SpotBugs + find-sec-bugs → SARIF + PR comment
  pmd:             # PMD → SARIF + PR comment
  coverage:        # JaCoCo report → Madrapps/jacoco-report PR comment
  gitleaks:        # Gitleaks → fails PR on a hit
  dependency-review:  # actions/dependency-review-action@v4 (PR-only)
```

All jobs run in parallel; the matrix completes in ~2 min thanks to the warm
Maven cache from `ci.yml`. None of them gate on `ci.yml` — they read the same
sources independently.

**Failure policy (initially conservative, can tighten later):**

| Job | Fails PR on | Rationale |
|---|---|---|
| `spotbugs` | High-confidence bugs + any find-sec-bugs **HIGH** finding | Security regressions block merge; low-conf bugs annotate only. |
| `pmd` | New rule violations in *changed lines only* | PMD on the whole codebase = noisy; use `pmd-github-action`'s "modified-only" mode. |
| `coverage` | **Never** | Per minimum-test policy. We *show* coverage delta, don't enforce. |
| `gitleaks` | Any finding | Secrets are always a P0. Pre-merge block. |
| `dependency-review` | Any new HIGH or CRITICAL CVE | Replaces a paid SCA scanner; cheap signal. |

### 3.3 `codeql.yml` — GitHub-native SAST

Generated from GitHub's Java template, queries: `security-extended` +
`security-and-quality`. Schedule: weekly Sunday + on PR. Free on private.

### 3.4 `image.yml` — Docker image to ghcr.io

```
jobs:
  build-image:
    permissions: { contents: read, packages: write }
    - checkout
    - setup-java + ./mvnw -DskipTests package   (reuses build-test cache)
    - docker/setup-buildx-action
    - docker/login-action (ghcr.io, GITHUB_TOKEN)
    - docker/build-push-action (tags: sha-short, branch, latest, semver)
    - trivy-action (image scan; HIGH/CRITICAL → workflow warning, not failure
      while we settle on a baseline; flip to failure after one clean week)
```

Image naming: `ghcr.io/<owner>/complaints:{<sha7>,main,latest,v<semver>}`.

### 3.5 `deploy-test.yml` — stubbed, ENV-driven

Skeleton only. Has the right secret slots (`GCP_PROJECT_ID`, `GCP_SA_KEY`,
`TEST_VM_HOST`, `TEST_VM_USER`, `TEST_VM_SSH_KEY`) but the actual `ssh + docker
pull + docker run` body is `if: false`-gated until Phase 2's GCP test env
exists. Documented in `ENVIRONMENT_SETUP.md` so we don't reinvent it.

---

## 4. Supporting config files

| File | Purpose |
|---|---|
| `.github/dependabot.yml` | Weekly updates for `maven`, `github-actions`, `docker`. Auto-rebase. Auto-merge security-only PRs after green CI (via a tiny extra workflow). |
| `.github/codeql/codeql-config.yml` | Limit CodeQL to `src/main` (skip Flyway SQL + generated). |
| `.gitleaks.toml` | Minimal allowlist for test fixture tokens (e.g. example JWTs in `*Test.java`). |
| `spotbugs-exclude.xml` | Suppress Lombok-generated boilerplate findings (e.g. `EI_EXPOSE_REP` on entities). |
| `pmd-ruleset.xml` | Trim PMD's default ruleset to the rules we actually care about; matches `.editorconfig`. |
| `.github/PULL_REQUEST_TEMPLATE.md` | Already exists. Add a "CI checks expected to pass" reminder line. |

---

## 5. Secrets (GitHub repo settings → Secrets and variables → Actions)

| Secret | Required by | Phase |
|---|---|---|
| `GITHUB_TOKEN` | All (built-in, auto-provided) | Now |
| _none for ghcr.io_ | `image.yml` uses `GITHUB_TOKEN` | Now |
| `GCP_PROJECT_ID` | `deploy-test.yml` | Phase 2 (GCP test env) |
| `GCP_SA_KEY` (JSON) | `deploy-test.yml` | Phase 2 |
| `TEST_VM_HOST` / `TEST_VM_USER` / `TEST_VM_SSH_KEY` | `deploy-test.yml` | Phase 2 |
| `MSG91_*` / `FCM_*` | App runtime, not CI | Phase 3 / Phase 6 |

> **Never** add SonarCloud / Snyk / Codacy / Codecov tokens — those tools are
> intentionally out of scope (cost or noise).

---

## 6. Branch protection (one-time GitHub UI setup)

After all workflows are green at least once on `main`, enable:

- **Require PRs** to merge into `main`.
- **Required status checks** (must pass before merge):
  - `build-test` (from `ci.yml`)
  - `openapi-drift` (from `ci.yml`)
  - `spotbugs`, `pmd`, `gitleaks`, `dependency-review` (from `quality.yml`)
  - `analyze (java)` (from `codeql.yml`)
- **Require branches up to date** before merging.
- **Auto-merge** allowed for Dependabot security PRs.
- **No bypassing for admins** — yes, including the repo owner. Cheap discipline.

---

## 7. Rollout plan — 3 small PRs

Each PR is independently green and shippable. Total ETA: half a day of
focused work, spread across whenever Stage 7 (FE) needs review time anyway.

### PR #1 — `ci.yml` + Dependabot + Maven cache
- **Files:** `.github/workflows/ci.yml`, `.github/dependabot.yml`
- **Risk:** lowest. Just runs `./mvnw verify` in CI.
- **Done when:** PR check is green; opening any subsequent PR shows the
  `build-test` + `openapi-drift` checks.

### PR #2 — `quality.yml` + `codeql.yml` + secret scan
- **Files:** `.github/workflows/quality.yml`, `.github/workflows/codeql.yml`,
  `.github/codeql/codeql-config.yml`, `.gitleaks.toml`, `spotbugs-exclude.xml`,
  `pmd-ruleset.xml`, `pom.xml` (add jacoco / spotbugs / pmd plugin
  declarations with `<skip>true</skip>` defaults so local `mvn verify` doesn't
  slow down — CI passes `-Dspotbugs.skip=false` etc.).
- **Risk:** medium — SpotBugs / PMD will surface a baseline of findings on
  Lombok-heavy code. **Strategy:** ship in *report-only* mode for the first
  PR (fails nothing); tighten failure thresholds in a follow-up PR after we
  triage the baseline. Same pattern Spring / Apache projects use.
- **Done when:** PRs get SpotBugs / PMD / coverage comments; Gitleaks blocks
  a deliberately-leaked test token; CodeQL alerts appear in Security tab.

### PR #3 — `image.yml` + ghcr.io + Trivy + `deploy-test.yml` stub
- **Files:** `.github/workflows/image.yml`, `.github/workflows/deploy-test.yml`,
  small `Dockerfile` review (we already have one), update to
  `ENVIRONMENT_SETUP.md` documenting `ghcr.io/<owner>/complaints`.
- **Risk:** low — image push is well-trodden.
- **Done when:** a push to `main` publishes `ghcr.io/<owner>/complaints:main`
  and a SHA-tagged image; Trivy report is visible in the run log; pulling
  the image locally works (`docker pull ghcr.io/...:main && docker run`).

---

## 8. What we are *not* doing (and why)

| Skipped | Why |
|---|---|
| **SonarCloud** | Free only for public repos; pricey for private (~$10/contributor/mo). SpotBugs + PMD + JaCoCo + CodeQL together cover the same surface. Revisit if we open-source. |
| **Self-hosted SonarQube CE** | Software is free, but the VM, Postgres, and ops time aren't. Doesn't pay for itself at our size. |
| **Snyk / Mend / Codacy / Codecov (paid tiers)** | All have free tiers but with hard caps and / or paid private-repo gating. Dependency Review + Dependabot + Trivy give 80 % of the signal at 0 cost. |
| **Self-hosted runners** | No infra cost only if you already own the box. We don't. GitHub-hosted is plenty for a 3-min build. |
| **Lighthouse / size-limit / playwright** | Frontend concerns; live in `complaints-frontend/`'s CI. |
| **Hard coverage gate** | Conflicts with the minimum-test policy. Reported, not enforced. |
| **Mutation testing (PIT)** | Worth it later if a service repeatedly ships regressions; over-kill for v1. |
| **Sigstore / cosign image signing** | Worth adding in Phase 7 (production hardening); not required for ghcr.io test images. |
| **Renovate** | Dependabot covers our ecosystems; one less account to manage. |
| **Actual cloud deploy in CI today** | Phase 2 `ENVIRONMENT_SETUP.md` is the gate. Until the test VM exists, deploying nowhere wastes minutes. |

---

## 9. Open questions — resolved 2026-06-22

| # | Question | Answer | Implication |
|---|---|---|---|
| 1 | Public or private repo? | **Public** | SARIF uploads from SpotBugs / PMD / Trivy will appear in the GitHub **Security** tab for free (no Advanced Security needed). The PR-comment fallback noted in §2 is unnecessary. **Still skip SonarCloud** — even though it's free for public repos, the SpotBugs + PMD + JaCoCo + CodeQL stack already covers the same surface and avoids the extra account / token / dashboard. |
| 2 | ghcr.io image namespace owner? | **`SunilGithub123`** | Images published as **`ghcr.io/sunilgithub123/complaints`** (ghcr.io lowercases the owner segment automatically). |
| 3 | Auto-merge Dependabot security PRs after green CI? | **Yes** | Adds a tiny `dependabot-auto-merge.yml` workflow: on `pull_request_target` from `dependabot[bot]`, if the update is a `security` or `version-update:semver-patch`, enable GitHub auto-merge. Major / minor non-security updates still require human review. |

---

## 10. Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-06-21 | **Skip SonarCloud / SonarQube.** Use SpotBugs + PMD + JaCoCo + CodeQL + Trivy + Dependency Review + Gitleaks instead. | SonarCloud is free for public repos only; self-hosted SonarQube has unavoidable infra cost. The OSS stack covers the same surface at zero cost. |
| 2026-06-21 | **ghcr.io, not Docker Hub.** | Free private quota, pushes with built-in `GITHUB_TOKEN`, no extra account. Docker Hub free tier rate-limits anonymous pulls — friction with future CD. |
| 2026-06-21 | **Stub `deploy-test.yml` now, wire it when GCP test env lands.** | Avoids re-design later; signals intent without spending CI minutes on a deploy with no target. |
| 2026-06-21 | **No hard coverage % gate.** | Per `.github/copilot-instructions.md → Minimum-test policy`. We *report* coverage via JaCoCo + PR comment. |
| 2026-06-22 | **Repo is public; still skip SonarCloud.** | SonarCloud would be free here, but SpotBugs + PMD + JaCoCo + CodeQL already cover its findings and avoid an extra account / token / dashboard to maintain. Reconsider only if reviewers start asking for Sonar-specific metrics (cognitive complexity, duplications view). |
| 2026-06-22 | **ghcr.io namespace = `sunilgithub123`.** | Personal-account scoped; matches the repo owner. |
| 2026-06-22 | **Enable Dependabot auto-merge for security + patch updates.** | Green CI is the gate; no human bottleneck for routine patches. Minor / major still require review. |

---

## 11. Useful pointers

- Tech-stack rationale: [`TECH_STACK.md`](TECH_STACK.md)
- Conventions enforced in CI: [`.github/copilot-instructions.md`](../.github/copilot-instructions.md)
- Local dev setup (mirrors CI's JDK / Docker prerequisites): [`ENVIRONMENT_SETUP.md`](ENVIRONMENT_SETUP.md)
- ArchUnit rules CI runs as part of `mvn verify`: [`src/test/java/com/example/complaints/architecture/PackageBoundaryTest.java`](../src/test/java/com/example/complaints/architecture/PackageBoundaryTest.java)
- Test policy CI honours: `.github/copilot-instructions.md` → **Minimum-test policy**

