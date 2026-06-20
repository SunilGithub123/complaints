# Frontend Design Document
## Complaint Resolution System

> Companion to `BRD.md` + `TECHNICAL_DESIGN.md`. Covers everything client-side: web portal, mobile app, shared code, build/deploy.
>
> **Status:** proposal — items flagged with **⚠ DECIDE** need your sign-off before scaffolding starts.

---

## 1. Goals & Constraints

- **Two clients in v1:**
  - **Web Portal** — Consumers (no login), Engineers, Admins. Shipped as a **PWA** (installable, offline-shell, low-bandwidth friendly — critical for 2G/3G rural users).
  - **Mobile App** — Technicians, Engineers.
- **Consumer mobile app** is v2 — consumers use the web PWA in v1 (responsive + installable from the browser).
- **Three languages eventually:** English (default), Hindi, Marathi (per BRD §5).
- **Low-bandwidth friendly:** target consumers may be on 2G/3G in rural Maharashtra → strict performance budgets (see §13), aggressive code-splitting, image-light, lazy-load everywhere.
- **One source of truth** for API contracts: the backend's OpenAPI 3 spec (`/v3/api-docs`) drives generated TypeScript clients on both web and mobile.

### Guiding Principles

1. **Production-grade from day one** — no "we'll add tests / security headers / observability later". Every PR runs the full CI gate (lint, unit, contract, a11y, bundle size, license scan). See §13.
2. **Scale-ready architecture** — stateless SPA + CDN, idempotent mutations, server-side pagination everywhere, no in-memory caches that would break under HPA. See §14.
3. **Cost-balanced** — prefer OSS + GCP free tiers; pay only where it materially raises quality (Sentry, EAS). Total v1 FE infra cost ≤ **$30/month** for test, **$50–150/month** for prod at v1 scale. See §15.
4. **Boring tech wins** — pick the choice with the largest community / longest support window when two options are roughly tied. Avoids paying the maintenance tax for novelty.

---

## 2. Architecture at a Glance

```
                       ┌─────────────────────────────────────────────────┐
                       │   complaints-frontend  (pnpm monorepo)          │
                       │                                                 │
                       │   apps/                                         │
                       │   ├── web/        React 19 + Vite (TS)          │
                       │   └── mobile/     React Native 0.76 + Expo (TS) │
                       │                                                 │
                       │   packages/                                     │
                       │   ├── api/        Generated OpenAPI client + Zod│
                       │   ├── ui-tokens/  Colors, spacing, typography   │
                       │   ├── i18n/       en/hi/mr message catalogues   │
                       │   └── utils/      Date (IST), formatters, etc.  │
                       └─────────────────────────────────────────────────┘
                                            │ HTTPS / JSON
                                            ▼
                       ┌─────────────────────────────────────────────────┐
                       │   Spring Boot backend  (this repo)              │
                       └─────────────────────────────────────────────────┘
```

- **Monorepo (pnpm workspaces + Turborepo)** keeps the API client + i18n + design tokens in one place; web and mobile import them as packages.
- **No SSR** in v1 — pure SPA. The web app is a static bundle deployed to GCS + Cloud CDN in prod.

---

## 3. Tech Stack (proposed)

### 3.1 Web Portal (`apps/web/`)

| Concern | Choice | Why |
|--------|--------|-----|
| Framework | **React 19** | Latest stable; built-in Actions / `use()` simplify form code |
| Build | **Vite 6** | Fast HMR, ESM-first, no webpack config |
| Language | **TypeScript 5.5+** (strict) | Type-safe end-to-end with shared API package |
| Routing | **React Router 7** (data routes) | Mature, well-documented; loader/action APIs fit our flows |
| Server state | **TanStack Query (React Query) v5** | Caching, retries, optimistic updates, devtools |
| Client state | **Zustand** | Minimal boilerplate; only for cross-page UI state (auth, locale, theme) |
| Forms | **React Hook Form + Zod** | Performant, schema-validated; Zod schemas shared from `packages/api` |
| UI kit | **shadcn/ui** (Radix primitives + Tailwind v4) | Copy-in components, fully themable, accessible, low bundle weight |
| Styling | **Tailwind CSS v4** | Utility-first; tokens come from `packages/ui-tokens` |
| Icons | **lucide-react** | Tree-shakeable SVG icons |
| Charts (admin dashboards, v2) | **Recharts** | Lightweight, React-native API |
| Tables | **TanStack Table v8** | Headless, virtualizes well for complaint lists |
| PWA / offline | **vite-plugin-pwa** (Workbox under the hood) | Installable, offline shell, background sync for the consumer flow |
| i18n | **react-i18next** + ICU MessageFormat | Plural/gender support for hi/mr |
| Date / time | **date-fns** + `date-fns-tz` | Lightweight, tree-shakeable, IST conversions |
| HTTP client | **fetch** wrapped by the generated `@complaints/api` package | No axios; smaller bundle |
| Testing (unit) | **Vitest + React Testing Library** | Aligns with Vite |
| Testing (E2E) | **Playwright** | Cross-browser, fast, replaces Selenium for FE flows |
| Linting | **ESLint 9** (flat config) + **Prettier** | Standard |

### 3.2 Mobile App (`apps/mobile/`)

| Concern | Choice | Why |
|--------|--------|-----|
| Framework | **React Native 0.76** (New Architecture: Fabric + TurboModules) | Latest stable, big perf gains |
| Tooling | **Expo SDK 52 (managed workflow)** | OTA updates, EAS Build/Submit, native modules without ejecting |
| Language | **TypeScript 5.5+** | Same strictness as web |
| Navigation | **React Navigation v7** (native stack + bottom tabs) | De facto standard |
| Server state | **TanStack Query v5** | Same as web, identical patterns |
| Client state | **Zustand** | Same as web |
| Forms | **React Hook Form + Zod** | Same as web |
| UI kit | **React Native Paper (Material 3)** | Built-in dark mode, accessibility, IST date pickers, lightweight |
| Styling | **StyleSheet** + tokens from `packages/ui-tokens` | Native primitives; avoid heavyweight CSS-in-JS |
| Icons | `@expo/vector-icons` | Bundled with Expo, broad coverage |
| Push notifications | **@react-native-firebase/messaging** | FCM, matches backend §7 |
| Secure storage (refresh token) | **expo-secure-store** (Keychain / Keystore) | OS-level encryption |
| Image upload + compression | **expo-image-picker** + **expo-image-manipulator** | Built-in compression to match 1 MB / 1280px limit |
| Date / time | **date-fns** + `date-fns-tz` | Shared with web |
| i18n | **react-i18next** | Shared catalogues from `packages/i18n` |
| HTTP client | Generated `@complaints/api` package | Same client both platforms |
| Testing (unit) | **Jest + React Native Testing Library** | Default for RN |
| Testing (E2E) | **Maestro** | Lightweight YAML-driven E2E, much simpler than Detox |
| CI builds | **EAS Build** | Cloud builds for iOS + Android, no local Xcode required |
| Distribution | EAS Submit → Play Store + App Store | Standard |

### 3.3 Shared Packages

- **`packages/api/`** — auto-generated TypeScript client + Zod schemas from the backend's `/v3/api-docs` (springdoc). Tooling: **orval** (`openapi-typescript` + Zod plugin + TanStack Query plugin). Re-generated via `pnpm api:gen` whenever the backend's OpenAPI changes; CI verifies the committed client is in sync.
- **`packages/i18n/`** — message catalogues `en.json`, `hi.json`, `mr.json` + `useT()` hook re-export. Single source of truth for both apps.
- **`packages/ui-tokens/`** — `colors.ts`, `spacing.ts`, `typography.ts`, `radius.ts`. Web consumes via Tailwind preset; mobile consumes via plain JS.
- **`packages/utils/`** — `dateIst.ts` (always-IST formatters), `currency.ts`, `phone.ts` (E.164 validation for Indian numbers), `errorCodes.ts` (maps backend `ErrorCode` enum strings to localized messages).

---

## 4. Repo Layout (locked)

**Decision (locked):** the frontend monorepo lives in a **separate Git repo** (`complaints-frontend`), sibling to the backend repo, at:

```
~/Java Project/myProjects/
├── complaints/                  ← backend  (Spring Boot, Maven)
└── complaints-frontend/         ← frontend (pnpm, Turborepo) — this doc
```

Rationale: independent release cadence, separate CI lanes (Maven vs Node), no risk of shipping API keys with the JAR, smaller clones for contributors who only work on one side, and OpenAPI is the natural decoupling boundary.

```
complaints-frontend/
├── package.json              # workspace root
├── pnpm-workspace.yaml
├── turbo.json                # Turborepo task pipeline
├── tsconfig.base.json
├── .nvmrc                    # Node 22 LTS
├── .editorconfig
├── apps/
│   ├── web/
│   │   ├── index.html
│   │   ├── vite.config.ts
│   │   ├── tailwind.config.ts
│   │   └── src/
│   │       ├── main.tsx
│   │       ├── routes/                     # React Router data routes
│   │       │   ├── _public/                # landing, language picker
│   │       │   ├── consumer/               # OTP gate + submit + list (no login)
│   │       │   ├── staff/
│   │       │   │   ├── login.tsx
│   │       │   │   ├── force-change-password.tsx
│   │       │   │   └── _layout.tsx         # JWT guard + role-based nav
│   │       │   ├── admin/
│   │       │   ├── engineer/
│   │       │   └── error/
│   │       ├── features/                   # feature folders (auth, complaint, staff-mgmt, masterdata)
│   │       ├── components/                 # shadcn/ui-derived primitives + shared widgets
│   │       ├── hooks/
│   │       ├── stores/                     # Zustand stores
│   │       ├── lib/                        # axios-less fetch wrapper, env, logger
│   │       └── styles/
│   └── mobile/
│       ├── app.config.ts                   # Expo config (env-driven)
│       ├── App.tsx
│       └── src/
│           ├── navigation/                 # RootStack, TechnicianTabs, EngineerTabs
│           ├── screens/
│           │   ├── auth/                   # LoginScreen, ForceChangePasswordScreen
│           │   ├── technician/             # AssignedList, Detail, Start, Resolve, Close, ImageUpload
│           │   └── engineer/               # InboxList, Detail, Assign, Reassign, CreateTechnician
│           ├── components/
│           ├── hooks/
│           └── lib/
├── packages/
│   ├── api/                                # generated client (do not hand-edit)
│   │   ├── package.json
│   │   ├── orval.config.ts
│   │   └── src/                            # `pnpm api:gen` writes here
│   ├── i18n/
│   │   ├── package.json
│   │   └── src/
│   │       ├── en.json
│   │       ├── hi.json
│   │       ├── mr.json
│   │       └── index.ts
│   ├── ui-tokens/
│   └── utils/
└── .github/workflows/
    ├── web-ci.yml                          # lint + test + build web
    ├── mobile-ci.yml                       # lint + test + EAS preview build
    └── api-sync-check.yml                  # ensure committed @complaints/api matches latest backend openapi.json
```

---

## 5. UX Flows (per role)

### 5.1 Consumer (Web Portal — no login)

```
Landing
   ├── [I am a Consumer]                ──▶ Consumer Verify modal
   │                                          ↓ (Consumer ID + Mobile)
   │                                       Send OTP  ←─┐
   │                                          ↓        │ resend (30s cooldown)
   │                                       Enter OTP ──┘
   │                                          ↓ (verify ⇒ 5-min token in memory)
   │       ┌──────────────────────────────────┴──────────────────────────────┐
   │       ▼                                                                 ▼
   │   Submit Complaint                                                  My Complaints
   │   ─────────────────                                                 ─────────────
   │   - Category dropdown (from /categories)                            - Paginated list
   │   - Description (textarea)                                          - Filter by status
   │   - Location (text; geo-locate in v2)                               - Tap → detail
   │   - Upload up to 3 images (compressed client-side)                       ├── status timeline
   │   - Submit ⇒ ticket number screen + share button                         ├── proof images
   │                                                                          ├── Cancel (if SUBMITTED)
   │                                                                          └── Feedback (if CLOSED, one-shot)
   │
   └── [Staff Login →]                ──▶ /staff/login
```

- **5-min token countdown** shown in the top bar; auto-redirect to "Verify again" modal on expiry (in-place — preserves draft complaint).
- **Draft persistence** in `sessionStorage` so an OTP timeout doesn't lose typed text.
- **Resend OTP** button disabled for 30s (matches backend cooldown).

### 5.2 Staff Login (Web + Mobile)

```
/staff/login
   ▼ Employee ID + Password
   POST /auth/login
   ▼
   if password_reset_required ⇒ /staff/force-change-password (only route accessible)
   else                       ⇒ role-based home (/admin, /engineer, technician tabs)
```

- Refresh token stored in **httpOnly secure cookie** on web (set by a thin BFF endpoint — see §7.2) and in **expo-secure-store** on mobile.
- Access token kept **in memory only** (Zustand store, cleared on tab close).
- TanStack Query is configured with a global `onError` that detects `401` → silently calls `/auth/refresh` → retries the original request once; if refresh fails, redirects to login.

### 5.3 Admin (Web)

Bottom-level navigation tree:

- **Dashboard** — counts by status / SLA breach / per-DC heatmap.
- **Complaints** — list, filter (status, severity, DC, technician, date range, search), detail view with full history, **assign / reassign across DCs**, severity, reject, mark-duplicate, **close on behalf**.
- **Staff** — list (filter by role + DC), create engineer, create technician, enable/disable. Form shows the initial password to the admin (with copy button + warning to share over a secure channel + reminder that the user is forced to change on first login).
- **Master Data** — subdivisions, distribution centers, categories, SLA config. Soft-delete shows a confirmation modal listing what will cascade (DCs, staff to disable).
- **Audit** — searchable view of `audit_log` (v2 may move to a separate dashboard).
- **EB Data Sync** — CSV upload page for the initial bulk dump; shows per-run summary.

### 5.4 Engineer (Web + Mobile)

- **Inbox** (default) — all complaints in the engineer's DC, sorted by SLA-deadline ascending. Bulk-assign in v2.
- **Complaint detail** — full history + images + proof images; primary actions: **Assign** (technician + severity in one form), **Reassign**, **Severity**, **Reject**, **Mark Duplicate**, **Close on behalf**.
- **Technicians** — list of technicians under this DC, create / enable / disable.
- **Notifications bell** — unread count from `/notifications`.

### 5.5 Technician (Mobile only in v1)

Bottom tabs:
- **Assigned** — list of `ASSIGNED` + `IN_PROGRESS` complaints, sorted by SLA-deadline asc.
- **In Progress** — quick filter.
- **History** — `RESOLVED` + `CLOSED` (last 30 days).

Per-complaint screen:
- Status + SLA countdown badge (red if breached).
- Consumer contact button → tel: link to `contact_mobile` from the complaint.
- Address / location → "Open in Maps" button.
- Image gallery (consumer images + proof images).
- Bottom action bar: **Start** → **Resolve** (form: notes + optional proof images) → **Close**.
- If SLA breached, the **Resolve / Close** form requires a `slaBreachReason` text area.

---

## 6. Cross-Cutting Concerns

### 6.1 Auth handling

| Token | Where stored | Refresh policy |
|-------|--------------|----------------|
| **Staff access JWT** | In-memory Zustand store | Silently refreshed on 401 once |
| **Staff refresh JWT** (web) | **httpOnly + Secure + SameSite=Lax cookie**, scoped to API origin | Rotated on every `/auth/refresh`; old cookie cleared |
| **Staff refresh JWT** (mobile) | `expo-secure-store` (Keychain / Keystore) | Same rotation policy |
| **Consumer verification JWT** | In-memory only (Zustand) | **Never refreshed** — re-run OTP flow when expired |

A small route guard component (`<RequireAuth role="ADMIN" />`) wraps protected routes; redirects to `/staff/login` if missing, to `/staff/force-change-password` if the access-token claim says so.

### 6.2 i18n

- Default locale: **English**. Locale picker in the header (web) and Settings (mobile).
- Persisted in `localStorage` (web) / `AsyncStorage` (mobile).
- Backend `ErrorCode` strings → `errorCodes.<CODE>` keys in the message catalogue, so consistent localized messages for every backend error.
- Numbers + dates always formatted with the **`en-IN`** locale (Indian digit grouping); date strings always rendered in **IST**.

### 6.3 Accessibility

- All shadcn / RN Paper components are accessible by default.
- WCAG 2.1 AA contrast checks in CI via `axe-core` (web) and `eslint-plugin-react-native-a11y` (mobile).
- Form fields always have visible labels (no placeholder-only inputs) — important for older users + screen readers.

### 6.4 Error handling & toasts

- Single `useApi()` hook wrapping TanStack Query — automatically maps the backend's `ApiResponse.error.code` to a localized toast via the `i18n` `errorCodes.*` table.
- Errors with no mapped code fall back to a generic "Something went wrong" toast and are reported to **Sentry** (web + mobile SDK).

### 6.5 Telemetry

- **Sentry** for unhandled errors + performance traces (both apps).
- **Web Vitals** reported to GCP Cloud Monitoring via the OpenTelemetry web SDK (prod only).
- **Mobile crash reporting** via Sentry + Firebase Crashlytics (defence in depth).

### 6.6 Offline behaviour

- **Web (consumer):** complaint draft auto-saved to `sessionStorage`; if the user loses connectivity mid-submit, retry banner with the cached payload.
- **Mobile (technician):** TanStack Query's `persistQueryClient` adapter backed by `AsyncStorage` keeps the *Assigned* list readable offline. Mutations (Start / Resolve / Close) queue with `mutation persistence` and fire when connectivity returns. Image uploads queue separately.
- **Conflict policy:** mutations include the complaint's `version` (added to backend `complaint` table as `OPTIMISTIC LOCK` column — *minor backend change*) so a stale offline action is rejected with `409 COMPLAINT_VERSION_CONFLICT` instead of silently overwriting.
  > **⚠ DECIDE:** Add `version BIGINT` optimistic-lock column to `complaint`? Tiny backend change; pays off the moment the mobile app goes offline-friendly.

### 6.7 Image handling

- Web: `<input type="file" accept="image/jpeg,image/png">` → client-side resize via `browser-image-compression` → upload (multipart) to `/consumer/complaints/{id}/images` or `/technician/complaints/{id}/resolution-images`.
- Mobile: `expo-image-picker` → `expo-image-manipulator` (resize to max 1280px, JPEG quality 0.8) → multipart upload.
- Backend size cap (1 MB) is enforced after client-side compression; we reject and ask the user to retake the picture if still too large.

### 6.8 Real-time updates (v2 candidate)

In v1, lists are refreshed via TanStack Query's `refetchOnWindowFocus` + a 60-second background refetch. Server-sent events / WebSocket / Firestore mirror is deferred.

---

## 7. Backend Touchpoints (small additions requested)

These are *minor* additions to the Spring Boot side that the frontend will need:

1. **Optimistic-lock `version` column** on `complaint` (see §6.6) — needed for offline-safe mutations on mobile.
2. **`/v3/api-docs` always exposed** (gated by HTTP Basic in test/prod per §6 TD) so the frontend CI can pull and regenerate the client.
3. **A thin BFF endpoint** (`POST /auth/login/web`) that does the same as `/auth/login` but additionally **sets the refresh token as an httpOnly cookie** on the response, so the SPA never sees it in JS. Optional but recommended for XSS hardening.
   - Companion `POST /auth/refresh/web` reads the cookie and rotates it.
4. **CORS preflight** for the FE origins (already wired per profile — TD §16.6).

> **⚠ DECIDE:** Do we add the BFF cookie endpoint (#3), or keep the refresh token in `localStorage` for simplicity? Recommended: add it.

---

## 8. Build & Deploy

### 8.1 Web

**Hosting strategy (locked):** GCS static-website bucket in **both** test and prod, so test exactly mirrors prod (any cache / CSP / SPA-routing bug shows up in test, not in prod). Test skips Cloud CDN (free); prod adds Cloud CDN for global edge caching.

| Env | Hosting | Domain | Monthly cost |
|-----|---------|--------|--------------|
| **Dev** | `pnpm --filter web dev` on `http://localhost:5173`, proxying `/api` → `http://localhost:8080` | localhost | $0 |
| **Test** | Static build → `gsutil rsync` to `gs://complaints-web-test/` → GCS website hosting (public bucket). Same backend VM serves `/api`; CORS allow-list adds the bucket URL. | bucket URL (or `https://test.<domain>` via DNS CNAME, optional) | **~$0.05/mo** (10 MB stored, < 1 GB egress at $0.08/GB) |
| **Prod** | Static build → `gs://complaints-web-prod/releases/<git-sha>/` → **Cloud CDN + Google-managed SSL + custom domain** | `https://complaints.maharashtra.gov.in` (TBD) | **$5–15/mo** |

**Test env GCS setup (one-time, ~5 min):**

```bash
# 1. Create the bucket (in the test GCP project)
gcloud storage buckets create gs://complaints-web-test \
  --location=asia-south1 \
  --uniform-bucket-level-access \
  --default-storage-class=STANDARD

# 2. Make it a public website
gcloud storage buckets update gs://complaints-web-test \
  --web-main-page-suffix=index.html \
  --web-error-page=index.html        # SPA fallback — all 404s serve the SPA

# 3. Make objects publicly readable (test only — prod uses signed URLs via CDN)
gcloud storage buckets add-iam-policy-binding gs://complaints-web-test \
  --member=allUsers --role=roles/storage.objectViewer
```

After this the app is reachable at `https://storage.googleapis.com/complaints-web-test/index.html` (or set up a CNAME on a sub-domain you control for a friendlier URL — free).

**Cache headers** (set at upload time, same in test and prod):

```bash
# Hashed asset files — cache forever
gsutil -m -h "Cache-Control:public,max-age=31536000,immutable" \
       rsync -r -x "index\\.html$" dist/ gs://complaints-web-test/
# index.html — always revalidate
gsutil -h "Cache-Control:no-cache,must-revalidate" \
       cp dist/index.html gs://complaints-web-test/index.html
```

**Backend CORS update (test):** the backend's `CORS_ALLOWED_ORIGINS` env var on the test VM must include the bucket's public URL (e.g. `https://storage.googleapis.com`) — single line change in `/etc/complaints.env`.

**CI flow** (in the FE repo, on merge to `main`):

```yaml
- run: pnpm install --frozen-lockfile && pnpm --filter web build
- uses: google-github-actions/auth@v2
  with: { workload_identity_provider: ${{ secrets.WIF_TEST }}, service_account: ${{ secrets.SA_TEST }} }
- uses: google-github-actions/setup-gcloud@v2
- name: Deploy to test bucket
  run: |
    gsutil -m -h "Cache-Control:public,max-age=31536000,immutable" \
           rsync -r -d -x "index\\.html$" apps/web/dist/ gs://complaints-web-test/
    gsutil -h "Cache-Control:no-cache,must-revalidate" \
           cp apps/web/dist/index.html gs://complaints-web-test/index.html
```

Total wall-clock deploy time: ~10 s after the build. Rollback = `gsutil rsync` from the previous git-SHA artifact (CI keeps the last 5 builds as workflow artifacts at zero cost).

### 8.2 Mobile

- **Internal QA builds:** every PR triggers EAS Build (preview profile) → install URL posted in the PR.
- **Beta:** TestFlight (iOS) + Internal Testing track (Play Store) auto-published on merge to `develop`.
- **Prod:** on tag `mobile-v*`, EAS Submit pushes to App Store + Play Store with `production` channel.
- **OTA updates** via EAS Update for JS-only changes (skip store review).

---

## 9. Testing Strategy

### 9.1 Test pyramid

| Layer | Web | Mobile |
|-------|-----|--------|
| Unit | Vitest + RTL | Jest + RN Testing Library |
| Component | Storybook stories (also serve as smoke tests) | Storybook for RN |
| E2E | Playwright on Chromium | Maestro flows for the technician happy path |
| Accessibility | `@axe-core/playwright` on key pages | `eslint-plugin-react-native-a11y` + Maestro a11y assertions |
| Contract (v2) | Pact (consumer side) verifying against the backend's Pact provider tests | Same |

### 9.2 Minimum-test policy (v1)

Same philosophy as backend TD §14.2 — **few, high-signal tests** rather than coverage theatre:

- **Per hook / utility:** **1 happy path + 1 failure** Vitest test. Skip if it's a 3-line passthrough.
- **Per form / screen:** **1 RTL test** for the success submission flow + **1** asserting that a validation error renders. No tests for "renders without crashing" boilerplate.
- **Per route:** covered by Playwright if it's one of the critical user journeys (see below), otherwise not.
- **E2E (Playwright)** — only **4 critical journeys** in v1: *consumer-submit-complaint*, *staff-login-and-force-change-password*, *engineer-assign-complaint*, *admin-create-engineer*.
- **E2E (Maestro on mobile)** — only **1 critical journey** in v1: *technician-start-resolve-close*.
- **A11y:** Lighthouse + `axe-core` run on every PR (already a perf-budget step §11.2) — those *are* the a11y tests.

### 9.3 No hard coverage gate in v1

Tracked informally; expect ~60% organically. Hard gates introduced after v1 stabilises.

---

## 10. Phasing

| Phase | Scope | Why first |
|-------|-------|-----------|
| **F1** | Monorepo scaffold + `packages/api` generation + `packages/i18n` skeleton (en only) + web `staff/login` + `staff/force-change-password` flow + **CI hardening gate** (lint, test, size-limit, lighthouse, license scan) | Unblocks every staff feature; CI gate from day one prevents regressions creeping in |
| **F2** | Web Admin: master data CRUD + staff create/list + CSV bulk-load | Lets the rest of the team seed real data manually |
| **F3** | Web Engineer + Admin: complaint list + assign / reassign / severity / reject / duplicate / close-on-behalf | Core operational flow |
| **F4** | Web Consumer: OTP flow + submit + list + cancel + feedback + **PWA install / offline shell / IndexedDB draft persistence** | Opens the system to end users for UAT; PWA is the v1 "consumer mobile app" |
| **F5** | Mobile (RN/Expo): technician tabs + engineer screens + FCM push | Field rollout |
| **F6** | i18n: hi + mr catalogues, locale picker, ICU pluralization audit | Compliance with BRD §5 |
| **F7** | Offline mode (mobile, queued mutations) + Sentry release tracking + WCAG 2.1 AA audit + Playwright/Maestro full coverage + load test of consumer flow | Hardening before prod cutover |

---

## 11. Production-Grade Hardening

Every item below ships **as part of F1** and is enforced by CI from the very first PR — no "we'll add it later" debt.

### 11.1 Security

| Threat | Mitigation |
|--------|------------|
| **XSS** | React's auto-escape + strict CSP (see below) + no `dangerouslySetInnerHTML` (ESLint rule: `react/no-danger`). All `innerHTML`/`eval`-style APIs forbidden. |
| **Token theft via XSS** | Staff refresh token in **httpOnly + Secure + SameSite=Lax cookie** (set by the BFF endpoint per D8). Access token in memory only (not even `localStorage`). Consumer verification token in memory only. |
| **CSRF** | SameSite cookies + `X-Request-Id` header on every mutating call; backend rejects mutations missing the header. (Bearer-style flows are CSRF-safe; cookie flow adds defence-in-depth.) |
| **CSP** | Strict Content-Security-Policy set at the CDN edge: `default-src 'self'; script-src 'self' https://browser.sentry-cdn.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://storage.googleapis.com; connect-src 'self' https://api.<env>.<domain> https://*.sentry.io https://fcmregistrations.googleapis.com; frame-ancestors 'none'; form-action 'self'; base-uri 'self'; upgrade-insecure-requests` + CSP-Report-Only for 2 weeks before enforcement. |
| **Clickjacking** | `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'`. |
| **MIME sniffing** | `X-Content-Type-Options: nosniff`. |
| **Referrer leakage** | `Referrer-Policy: strict-origin-when-cross-origin`. |
| **Permissions** | `Permissions-Policy: geolocation=(self), camera=(self), microphone=()` — geolocation reserved for v2; camera for the optional in-form photo capture. |
| **HSTS** | `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload` set at the CDN. |
| **Dependency CVEs** | **Dependabot** weekly + `pnpm audit --prod` as a blocking CI step (high/critical fails the build). |
| **Supply-chain** | `pnpm` lockfile committed; CI runs with `--frozen-lockfile`; **Sigstore / npm provenance** verification for top-level deps (where publishers support it). |
| **License compliance** | `license-checker` in CI; allow-list of OSI-approved permissive licenses (MIT / Apache-2.0 / BSD / ISC); GPL/AGPL fail the build. |
| **Secrets in code** | `gitleaks` pre-commit hook + GitHub secret scanning; **no `.env` files committed**; build-time secrets injected via GitHub Actions secrets. |
| **Source maps** | Generated only for Sentry upload (`sentry-cli sourcemaps upload`); **not deployed** to the public CDN bucket. |
| **iframe / 3rd-party SDKs** | None in v1 except Sentry browser SDK (loaded from `self`, not their CDN, to avoid the `unsafe-inline` script hash dance). |

### 11.2 Performance budgets (enforced in CI)

| Metric | Budget | Tool |
|--------|--------|------|
| **Initial JS (gzipped, consumer route)** | ≤ **180 KB** | `size-limit` blocking CI step |
| **Initial CSS (gzipped, consumer route)** | ≤ **20 KB** | `size-limit` |
| **Initial JS (gzipped, staff route)** | ≤ **300 KB** | `size-limit` |
| **Largest Contentful Paint** (Slow 4G throttling, mobile) | ≤ **2.5 s** | `lighthouse-ci` |
| **Total Blocking Time** | ≤ **300 ms** | `lighthouse-ci` |
| **Cumulative Layout Shift** | ≤ **0.1** | `lighthouse-ci` |
| **Time to Interactive** (3G throttling, mobile) | ≤ **5 s** | `lighthouse-ci` |
| **Lighthouse Accessibility score** | ≥ **95** | `lighthouse-ci` |
| **Lighthouse SEO score** (consumer routes) | ≥ **90** | `lighthouse-ci` |
| **Lighthouse PWA score** | ≥ **90** | `lighthouse-ci` |
| **Mobile app cold start (Android mid-range)** | ≤ **3 s** | manual smoke + Maestro perf assertions |

Lighthouse CI runs **on every PR** against the built preview; budgets are committed in `.lighthouserc.json`. Bundle size budgets are part of the same `pnpm build` step.

### 11.3 Observability

| Signal | Web | Mobile | Sampling |
|--------|-----|--------|----------|
| Uncaught errors | Sentry browser SDK | Sentry RN SDK | 100% |
| Performance traces | Sentry (`tracesSampleRate`) | Sentry | **10% in prod**, 100% in dev/test |
| Web Vitals (LCP, INP, CLS, FCP, TTFB) | `web-vitals` lib → Sentry + GCP Cloud Monitoring | n/a | 10% in prod |
| Native crash | n/a | Sentry + Firebase Crashlytics | 100% |
| Custom events (`complaint_submitted`, `otp_resent`, `staff_login_success`, …) | Sentry breadcrumbs | Sentry breadcrumbs | 100% |
| Release tracking | `sentry-cli releases new` in CI | EAS Update channel → Sentry release | every deploy |
| Health check | `/health.txt` served by CDN (static file) | n/a | — |

**Release identifiers** are the same git SHA across backend + frontend, so a single error trace can be correlated end-to-end.

### 11.4 Accessibility

- **Target standard:** WCAG 2.1 AA across all pages.
- **CI gates:**
  - `@axe-core/playwright` runs against every key route in every PR; **any AA violation fails the build**.
  - `eslint-plugin-jsx-a11y` (web) + `eslint-plugin-react-native-a11y` (mobile) at the lint stage.
- **Manual audit** before each prod release: keyboard-only navigation, screen reader smoke (VoiceOver on iOS, TalkBack on Android, NVDA on Windows).
- **Form rules:** every input has a visible `<label>`; error messages are programmatically associated via `aria-describedby`; focus is moved to the first invalid field on submit.
- **Color contrast** enforced via shadcn theme tokens — designers can't accidentally pick AA-failing combos.

### 11.5 CI / CD gates (every PR)

```
┌────────────────────────────────────────────────────────────────────┐
│ 1. pnpm install --frozen-lockfile                                   │
│ 2. license-checker  (allow-list)                                    │
│ 3. pnpm -r lint     (ESLint + Prettier + jsx-a11y)                  │
│ 4. pnpm -r typecheck                                                │
│ 5. pnpm -r test     (Vitest + Jest, 80% coverage gate on packages)  │
│ 6. pnpm --filter web build                                          │
│ 7. size-limit       (bundle budgets §11.2)                          │
│ 8. lighthouse-ci    (perf + a11y budgets)                           │
│ 9. playwright       (E2E + axe-core)                                │
│ 10. pnpm audit --prod  (high/critical CVEs fail)                    │
│ 11. gitleaks        (no secrets in diff)                            │
│ 12. api-sync-check  (committed @complaints/api matches openapi.json)│
└────────────────────────────────────────────────────────────────────┘
```

Any failed step **blocks merge** — no opt-outs.

### 11.6 Release & rollback

- **Web:** `gsutil rsync` into a **versioned GCS path** (`gs://complaints-web-prod/releases/<git-sha>/`) and atomically flip a CDN redirect (`/index.html` → newest release). Rollback = flip the pointer to the previous SHA — sub-minute.
- **Mobile (native shell):** EAS Submit with **staged rollout** (10% → 50% → 100% over 48 h). Rollback for native bugs = halt rollout in the Play/App Store consoles.
- **Mobile (JS / OTA):** EAS Update channels — `production` channel auto-served on app launch. Rollback = `eas update --republish` the previous update. Effective within minutes.
- **Backend coordination:** FE never assumes a feature without the OpenAPI spec advertising it — release order is **backend first, then FE**, gated by the `api-sync-check` CI step.

---

## 12. Scalability

### 12.1 Static delivery

- **HTML shell:** `Cache-Control: no-cache, must-revalidate` (always re-validated, but content rarely changes → 304s are cheap).
- **JS / CSS / images** (content-hashed filenames): `Cache-Control: public, max-age=31536000, immutable` — a year, never re-downloaded.
- **CDN:** **Cloud CDN** (prod) → global edge cache. Test env uses the GCS bucket's built-in HTTP fronting (no separate CDN).
- **Brotli + gzip** pre-compression at build time (Vite plugin) → CDN serves the pre-compressed asset, zero on-the-fly cost.
- **HTTP/2** / **HTTP/3** at the CDN — multiplexed asset fetch, low latency on poor links.

### 12.2 Bundle strategy

- **Route-level code-splitting** — React Router lazy routes; each role's bundle (`/admin`, `/engineer`, `/consumer`) is its own chunk. A consumer never downloads the admin code, and vice versa.
- **Vendor chunking** — `react`, `react-dom`, `@tanstack/*`, i18n, design system each in long-cached vendor chunks (separate from app code).
- **Tree-shaking** verified per PR via the `size-limit` step (catches accidental whole-library imports).
- **Dynamic import** of heavy widgets (charts, the image-compressor worker, the CSV upload page) so they only load when needed.
- **No moment.js, no lodash full import, no axios** — explicitly forbidden via ESLint `no-restricted-imports`.

### 12.3 Network resilience

- **TanStack Query** retry policy: exponential backoff, 3 attempts, jittered. Idempotent reads only — mutations retry only on network errors, not on 4xx/5xx.
- **`X-Request-Id`** header (ULID generated client-side) attached to every request → backend echoes it back → end-to-end trace + safe mutation idempotency (backend can dedupe on it if needed).
- **Slow-network UI** — skeleton screens for the first ~300 ms, then a real spinner; never block the entire page.
- **Offline-first PWA** (consumer web) — service worker caches the app shell + the categories list + the current consumer's draft. A submitted-while-offline complaint is queued via Background Sync and posted on reconnect.

### 12.4 Image handling at scale

- Client-side compression (browser-image-compression / expo-image-manipulator) targets **≤ 200 KB** per image (well under the 1 MB backend limit) — backend bandwidth stays low even with millions of uploads.
- Backend returns **signed GCS URLs**; FE renders them via `<img loading="lazy" decoding="async" srcset="...">` so the browser fetches them only when scrolled into view.
- v2: switch to **direct-to-GCS resumable uploads** (signed URL issued by backend, FE PUTs straight to GCS) — removes app servers from the upload path entirely.

### 12.5 Mobile scale

- **OTA updates** (EAS Update) decouple JS releases from store reviews → can ship hotfixes hourly if needed.
- **TanStack Query persistence** (AsyncStorage adapter) makes the technician's *Assigned* list available offline and survives app restarts.
- **Image upload queue** persists across app launches; retries with backoff until the device is online.
- Cold-start budget ≤ 3 s on mid-range Android — Hermes engine + minimal `App.tsx`.

### 12.6 Concurrency & state correctness

- **Optimistic-lock `version`** column (D9) on `complaint` → mobile mutations carry the version; the backend returns `409 COMPLAINT_VERSION_CONFLICT` on a stale write. UI shows a "This complaint was updated by someone else — refresh?" toast instead of silently losing the engineer's note.
- TanStack Query invalidations are surgical (`queryClient.invalidateQueries({ queryKey: ['complaint', id] })`) — no global "refetch everything" antipatterns.

### 12.7 What scales horizontally for free

- Static SPA + CDN → **infinite read scale** at near-zero marginal cost.
- All API state is server-owned (TanStack Query is just a cache) → swapping pods, blue/green deploys, autoscaling all transparent to the user.
- Consumer sessions are 5-min tokens in memory → **no sticky sessions** needed at the load balancer.

---

## 13. Cost Analysis

### 13.1 Per-environment monthly cost estimate

| Item | Dev (local) | Test | Prod (v1 scale: 1M consumers, 10K complaints/day) |
|------|-------------|------|----------------------------------------------------|
| Web hosting (GCS + Cloud CDN) | $0 | **~$0.05** (GCS bucket only, no CDN; < 1 GB egress) | **$5–15** (assume 10 GB egress/month at $0.08/GB after free) |
| Web SSL (Google-managed) | $0 | $0 | $0 |
| Sentry (errors + 10% perf sampling) | $0 (free tier 5k errors/mo) | $0 | **$0** if under free tier, else **Team plan $26/mo** for 50k events |
| EAS Build (mobile) | $0 (local builds) | $0 (Free plan, 30 builds/mo) | **$0** Free plan if ≤ 30 builds/mo, else **Production $99/mo** unlimited |
| EAS Update (mobile OTA) | $0 | $0 | **$0** (Free plan covers ≤ 1,000 MAU; v1 staff users only — well under) |
| EAS Submit | $0 | $0 | $0 (included in Free plan) |
| GitHub Actions (2 000 free min/mo) | $0 | $0 | **$0–10** (CI runtimes likely under free tier; pay-as-you-go beyond) |
| Dependabot / secret scanning | $0 | $0 | $0 (free on public/private GitHub repos) |
| Lighthouse CI server (self-hosted) | $0 | $0 | $0 (runs as a GH Actions step, no separate server) |
| Domain | $0 | $0 | **~$1/mo** (.in domain ~$10/yr) |
| Apple Developer Program | — | — | **$99/yr** (~$8/mo) — required to publish to App Store |
| Google Play Developer | — | — | **$25 one-time** (~$0/mo amortised) |
| **Total** | **$0** | **~$1** | **~$40–150/month** |

### 13.2 Choices vs alternatives — cost rationale

| Decision | Cheapest viable | Why we recommend that |
|----------|----------------|-----------------------|
| **Web host = GCS + Cloud CDN** | Same | Stays inside GCP (single bill, single console), pay-per-GB-egress is negligible at v1 scale, no vendor lock-in. **Firebase Hosting** has a bigger free tier (10 GB stored / 360 MB-per-day egress) but the same Google ecosystem and slightly higher per-GB cost above the free tier — fine to swap if test-env budget pressure appears. **Vercel** is great DX but $20/mo per seat + bandwidth — not justified for an SPA. |
| **Error tracking = Sentry free tier** | GlitchTip self-host (~$5/mo on a tiny VM) | Sentry's hosted free tier (5 k errors/mo) likely covers v1 prod. GlitchTip is Sentry-compatible and self-hostable if even that becomes too expensive — drop-in SDK swap. |
| **Mobile build = EAS Free** | Self-hosted CI building locally on a Mac mini | EAS Free is *enough*: 30 builds/month + unlimited Updates + Submit. Mac-mini self-hosting saves money only past ~100 builds/month and adds ops burden — not worth it in v1. |
| **Monorepo = pnpm + Turborepo (OSS)** | Same | Nx Cloud's remote cache is a paid feature — Turborepo's remote cache via Vercel is free for OSS, otherwise we use only local cache. Zero recurring cost. |
| **UI kit = shadcn/ui (copy-in)** | Same | Zero runtime dep weight, zero licensing. Material UI would pull in a larger bundle (impacts §11.2 budget). |
| **Visual regression = Playwright snapshots** | Same | Free, runs in GH Actions, stored as git artifacts. Chromatic ($149/mo+) only worth it if a designer team is doing daily diffs. |
| **Analytics = Sentry breadcrumbs + GCP Cloud Monitoring** | Same | We get user-flow visibility for free via Sentry + Lighthouse CI. Adding GA4/Mixpanel is unnecessary in v1; can add free GA4 later if marketing wants it. |
| **i18n strings = JSON in `packages/i18n`** | Same | No Crowdin/Lokalise subscription in v1; translators get GitHub access. We can adopt a TMS later if translator volume grows. |

### 13.3 Cost guard-rails

- **CDN egress alert:** GCP budget alert at $50/mo for the FE bucket — early warning if a bug causes runaway downloads.
- **Sentry quota alert:** at 80% of monthly event quota → triage noisy errors before paying for overage.
- **EAS build alert:** GitHub Action posts to Slack when monthly EAS build count hits 25/30 → forces conversation about CI optimisation before we upgrade tier.
- **No always-on FE services** — the entire frontend stack is static + serverless. There are no idle EC2/VMs / Cloud Run minimums.

### 13.4 What we deliberately *don't* spend on (yet)

| Item | Why deferred |
|------|--------------|
| **Vercel / Netlify hosting** | Same DX gain not worth $20+/mo per seat |
| **Chromatic visual regression** | Playwright snapshots cover the same ground for free |
| **Datadog / New Relic RUM** | Sentry + Web Vitals → GCP Cloud Monitoring covers this |
| **Crowdin / Lokalise TMS** | JSON-in-git workflow fine until ≥ 10 active translators |
| **LogRocket / FullStory session replay** | Privacy concerns (consumer mobile numbers in the form) + cost; revisit only on demand |
| **Storybook hosted (Chromatic / Vercel)** | Run Storybook locally + on PR previews via GH Pages if needed — free |
| **Mac mini / Mac in cloud for native builds** | EAS Free covers our cadence |

---

## 14. Open Decisions (need your input)

| # | Decision | Recommended default |
|---|----------|---------------------|
| **D1** ✅ | Separate `complaints-frontend` repo vs adding `apps/` to the backend repo | **LOCKED: separate repo** (see §4) |
| **D2** | UI kit for web: **shadcn/ui + Tailwind** vs **Material UI** | **shadcn/ui + Tailwind** (lighter bundle, copy-in customisation) |
| **D3** | UI kit for mobile: **React Native Paper** vs **Tamagui** vs **NativeBase** | **RN Paper** (Material 3, stable, smaller community risk) |
| **D4** | Expo **managed workflow** vs bare React Native | **Expo managed** (faster, EAS handles native builds; no Xcode locally) |
| **D5** | Server state lib: **TanStack Query** vs **RTK Query** | **TanStack Query** (lighter, used by both web + mobile identically) |
| **D6** | Client state lib: **Zustand** vs **Redux Toolkit** | **Zustand** (less ceremony, fine for our scope) |
| **D7** | API client generator: **orval** vs **openapi-typescript-codegen** vs hand-written | **orval** (generates types + Zod + TanStack Query hooks in one go) |
| **D8** | Add backend BFF cookie endpoint for refresh-token storage on web | **Yes** (XSS hardening) |
| **D9** | Add `version` (optimistic-lock) column on `complaint` for offline-safe mobile mutations | **Yes** (tiny backend change, big mobile win) |
| **D10** | Mobile E2E: **Maestro** vs **Detox** | **Maestro** (YAML, no native build needed, faster) |
| **D11** | Visual regression: **Chromatic** ($) vs **Playwright snapshots** (free) | **Playwright snapshots** in v1; Chromatic if design churn picks up |
| **D12** | Error reporting: **Sentry** vs **GCP Error Reporting** | **Sentry** (better SPA + RN support; free tier sufficient) |
| **D13** ✅ | Web deploy: **GCS + Cloud CDN** vs **Firebase Hosting** vs **Vercel** | **LOCKED: GCS website bucket in test (no CDN, ~$0.05/mo) and GCS + Cloud CDN in prod** (see §8.1) |
| **D14** | OTA updates on mobile via EAS Update | **Enabled** (skip store review for JS-only fixes) |
| **D15** | Monorepo tool: **pnpm + Turborepo** vs **Nx** | **pnpm + Turborepo** (lighter, less opinionated) |
| **D16** | **Consumer web as a PWA** (installable, offline shell, background sync) | **Yes** (replaces "consumer mobile app v2" for most users; tiny extra effort) |
| **D17** | **Performance + a11y budgets enforced in CI** from F1 (block merge on violation) | **Yes** (cheap to start, expensive to retrofit) |
| **D18** | Sentry **free tier first**, upgrade only when quota hits 80% | **Yes** |
| **D19** | Single git commit SHA used as the release identifier across backend + FE + Sentry | **Yes** (correlation for free) |

---

## 15. What I'll Build First (if you say "go")

1. Create `complaints-frontend` repo (separate folder).
2. Scaffold pnpm + Turborepo + Vite + Expo skeleton matching §4.
3. Wire `packages/api` with orval pointed at `http://localhost:8080/v3/api-docs` (will be empty until backend is up; that's fine).
4. Implement `apps/web/src/routes/staff/login.tsx` + `force-change-password.tsx` with the Zustand auth store and TanStack Query refresh interceptor.
5. Stand up a Storybook with the shadcn primitives we'll reuse (Button, Input, Card, Toast, Dialog, DataTable).
6. **Wire the full CI hardening gate** (§11.5) — lint, typecheck, test, size-limit, lighthouse-ci, playwright + axe, pnpm audit, gitleaks, license-checker. All blocking from PR #1.
7. Commit + open the first PR.

> Frontend work can start in parallel with backend scaffolding — the only contract that must exist first is the OpenAPI spec, which springdoc generates automatically the moment the backend boots.

---

