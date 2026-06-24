# Environment Setup Guide

Step-by-step setup for **Dev (local)**, **Test (GCP VM)**, and **Prod (GKE)** environments.

---

## Prerequisites

- Java 21 (`brew install openjdk@21`)
- Maven 3.9+ (`brew install maven`)
- Docker Desktop (`brew install --cask docker`)
- gcloud CLI (`brew install --cask google-cloud-sdk`)
- Git
- An IDE (IntelliJ IDEA recommended)

---

## 1. DEV — Local Setup ($0/month)

### 1.1 Clone & Build
```bash
git clone <repo-url>
cd complaints
./mvnw clean install -DskipTests
```

### 1.2 Start Infrastructure (Docker Compose)
Create `docker-compose.yml` in project root:
```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: complaints-postgres
    environment:
      POSTGRES_DB: complaints
      POSTGRES_USER: complaints
      POSTGRES_PASSWORD: complaints
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

> Caching uses **Caffeine (in-JVM)** — no Redis container needed for v1.

Start:
```bash
docker compose up -d
```

### 1.3 Run the App

On a **fresh database** (first run), set the bootstrap-admin env vars so `AuthBootstrapRunner` can create the initial admin user:

```bash
export BOOTSTRAP_ADMIN_EMPLOYEE_ID=ADMIN001
export BOOTSTRAP_ADMIN_PASSWORD='ChangeMe!123'
export BOOTSTRAP_ADMIN_SUBDIVISION_CODE=SUB-NSK-001   # must exist in `subdivision` table (seed it first via SQL or datasync)

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On subsequent runs the env vars are not required (the runner is a no-op once any admin exists). The bootstrap admin is created with `password_reset_required = TRUE`, so the **first login** must call `POST /auth/password/change` before anything else.

### 1.4 Verify
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Actuator health: <http://localhost:8080/actuator/health>
- Logs: console (OTP mock will print to console)
- Log in via Swagger using `POST /auth/login` with the bootstrap admin credentials above.
- **End-to-end smoke**: `./scripts/smoke.sh` drives the full happy path
  (admin login → engineer + tech bootstrap → consumer OTP → submit → assign →
  start → resolve → close → feedback → read-back) in one shot. Requires `jq`.
  To skip the interactive OTP prompt, boot the app with stdout redirected and
  point the script at the log file:
  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev > /tmp/complaints.log 2>&1 &
  APP_LOG_FILE=/tmp/complaints.log ./scripts/smoke.sh
  ```
  Exits non-zero on the first failing step with the response body dumped.
  Idempotent: re-running on a non-clean DB reuses the staff rows it created
  before (deterministic employee IDs `SMOKE_ENG_007` / `SMOKE_TECH_007`).

### 1.5 Stop
```bash
docker compose down              # keep data
docker compose down -v           # wipe data
```

### 1.6 Useful dev-only overrides

Spring Boot binds every `app.*` property to a matching env var via [relaxed binding](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.typesafe-configuration-properties.relaxed-binding). A few that come up during manual smoke testing:

| Env var | Default | Why you'd override it |
|---------|---------|-----------------------|
| `APP_OTP_COOLDOWN_SECONDS` | `30` | Set to `0` to exercise the `OTP_TOO_MANY_ATTEMPTS` lock branch without waiting 30s between OTP sends (FE Stage 11 reported this path is otherwise hard to hit in manual smoke). |
| `APP_OTP_MAX_ATTEMPTS` | `5` | Lower it (e.g. `2`) to trip the lock in two wrong submissions. |
| `APP_STORAGE_LOCAL_PATH` | `./uploads` | Point at a tmpfs path for faster IT-style local loops. |
| `APP_COMPLAINT_MAX_IMAGE_BYTES` | `1048576` | Raise it to debug the `IMAGE_TOO_LARGE` → 413 multipart handler against a non-compressed photo. |

These are *dev-only knobs* — never set them in test / prod profiles without a code change to match.

---

## 2. TEST — Cloud-hosted on GCP VM (~$15/month)

### 2.1 One-time GCP Setup
```bash
# Login
gcloud auth login

# Create project
gcloud projects create complaints-test --name="Complaints Test"
gcloud config set project complaints-test

# Enable billing (link your billing account via Console)
# Enable required APIs
gcloud services enable compute.googleapis.com \
                       storage.googleapis.com \
                       pubsub.googleapis.com \
                       logging.googleapis.com
```

### 2.2 Create VM
```bash
gcloud compute instances create complaints-test \
  --zone=asia-south1-a \
  --machine-type=e2-small \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --tags=http-server,https-server \
  --boot-disk-size=20GB

# Open firewall for app port
gcloud compute firewall-rules create allow-app-8080 \
  --allow=tcp:8080 \
  --target-tags=http-server
```

### 2.3 SSH into VM and Install Java + Docker
```bash
gcloud compute ssh complaints-test --zone=asia-south1-a

# Inside VM:
sudo apt update
sudo apt install -y openjdk-21-jdk docker.io docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker

# Start PostgreSQL container
docker run -d --name postgres \
  -e POSTGRES_DB=complaints \
  -e POSTGRES_USER=complaints \
  -e POSTGRES_PASSWORD=<strong-password> \
  -p 5432:5432 \
  -v /opt/pgdata:/var/lib/postgresql/data \
  postgres:16-alpine
```

### 2.4 Create GCS Bucket
```bash
gcloud storage buckets create gs://complaints-images-test \
  --location=asia-south1 \
  --uniform-bucket-level-access
```

### 2.5 Set Up External Services
- **MSG91** — Sign up at <https://msg91.com>, get sandbox API key.
- **Firebase** — Create project at <https://console.firebase.google.com>, download service account JSON for FCM.

> No Redis needed in test (Caffeine in-JVM cache is used).

### 2.6 Set Environment Variables on VM
```bash
# On VM, create /etc/complaints.env
sudo tee /etc/complaints.env > /dev/null <<EOF
SPRING_PROFILES_ACTIVE=test
DB_URL=jdbc:postgresql://localhost:5432/complaints
DB_USER=complaints
DB_PASSWORD=<strong-password>
GCP_PROJECT_ID=complaints-test
MSG91_KEY=<from-msg91>
FCM_SA_JSON_PATH=/etc/fcm-sa.json
JWT_SECRET=<random-256-bit>
CORS_ALLOWED_ORIGINS=https://complaints-test.example.in
SWAGGER_BASIC_USER=admin
SWAGGER_BASIC_PASSWORD=<strong-swagger-password>
TZ=Asia/Kolkata
BOOTSTRAP_ADMIN_EMPLOYEE_ID=ADMIN001
BOOTSTRAP_ADMIN_PASSWORD=<strong-bootstrap-password>
BOOTSTRAP_ADMIN_SUBDIVISION_CODE=SUB-NSK-001
EOF
```

### 2.7 Deploy via GitHub Actions
Create `.github/workflows/deploy-test.yml`:
```yaml
name: Deploy to Test
on:
  push:
    branches: [develop]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: ./mvnw clean package -DskipTests
      - name: Copy JAR to VM
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.TEST_VM_HOST }}
          username: ${{ secrets.TEST_VM_USER }}
          key: ${{ secrets.TEST_VM_SSH_KEY }}
          source: "target/complaints-*.jar"
          target: "/opt/complaints/"
      - name: Restart service
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.TEST_VM_HOST }}
          username: ${{ secrets.TEST_VM_USER }}
          key: ${{ secrets.TEST_VM_SSH_KEY }}
          script: sudo systemctl restart complaints
```

### 2.8 Set Up systemd Service on VM
```bash
sudo tee /etc/systemd/system/complaints.service > /dev/null <<EOF
[Unit]
Description=Complaints Service
After=network.target

[Service]
EnvironmentFile=/etc/complaints.env
ExecStart=/usr/bin/java -jar /opt/complaints/target/complaints-0.0.1-SNAPSHOT.jar
Restart=always
User=ubuntu

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable complaints
sudo systemctl start complaints
```

### 2.9 Access
- API: `http://<VM-EXTERNAL-IP>:8080`
- Swagger: `http://<VM-EXTERNAL-IP>:8080/swagger-ui.html` (HTTP Basic-gated)
- Logs: `journalctl -u complaints -f` or GCP Cloud Logging Console

### 2.10 Frontend on Test — GCS static-website bucket (~$0.05/month)

The web app (built in the separate `complaints-frontend` repo) is hosted as a public GCS website bucket, **mirroring the prod hosting model** so any CSP / cache / SPA-routing issue shows up in test, not in prod.

**One-time setup** (in the test GCP project):

```bash
# Create the bucket
gcloud storage buckets create gs://complaints-web-test \
  --location=asia-south1 \
  --uniform-bucket-level-access \
  --default-storage-class=STANDARD

# Configure as a SPA-friendly website
gcloud storage buckets update gs://complaints-web-test \
  --web-main-page-suffix=index.html \
  --web-error-page=index.html        # 404 fallback → SPA handles routing

# Make objects publicly readable (test only; prod uses signed URLs via Cloud CDN)
gcloud storage buckets add-iam-policy-binding gs://complaints-web-test \
  --member=allUsers --role=roles/storage.objectViewer
```

The site is then reachable at:

```
https://storage.googleapis.com/complaints-web-test/index.html
```

(Optional: point a CNAME from `test.<your-domain>` for a friendlier URL — free.)

**Update backend CORS** on the test VM so the FE origin is allowed:

```bash
# Append to /etc/complaints.env
CORS_ALLOWED_ORIGINS=https://storage.googleapis.com
```

…then `sudo systemctl restart complaints`.

**Deploy flow** (driven by GitHub Actions in the `complaints-frontend` repo on merge to `main`):

```bash
# Inside the FE repo CI:
pnpm install --frozen-lockfile
pnpm --filter web build

# Hashed assets — cache forever
gsutil -m -h "Cache-Control:public,max-age=31536000,immutable" \
       rsync -r -d -x "index\\.html$" apps/web/dist/ gs://complaints-web-test/

# index.html — always revalidate
gsutil -h "Cache-Control:no-cache,must-revalidate" \
       cp apps/web/dist/index.html gs://complaints-web-test/index.html
```

Wall-clock deploy time after build: **~10 seconds**. Rollback = re-run an older workflow's artifact upload step (FE CI keeps the last 5 builds).

**Cost:**
- GCS storage: ~10 MB × $0.020/GB-month ≈ **$0.0002/mo**
- Egress: < 1 GB/mo at $0.08/GB ≈ **$0.05/mo**
- Total FE-on-test: **~$0.05/month** (effectively a rounding error on the bill).

> Mobile app on test is *not* deployed to the VM — internal QA installs come from **EAS Build** preview URLs (also free under the EAS Free plan). See `FRONTEND_DESIGN.md §8.2`.

---

## 3. PROD — GKE Autopilot (Future, ~$700-1,100/month)

> Only summarized here; full prod runbook to be created when we approach launch.

### 3.1 Provision
```bash
gcloud container clusters create-auto complaints-prod \
  --region=asia-south1

gcloud sql instances create complaints-db-prod \
  --database-version=POSTGRES_16 \
  --region=asia-south1 \
  --tier=db-custom-2-8192 \
  --availability-type=REGIONAL    # HA

# Memorystore Redis is OPTIONAL — provision only if/when scaling beyond 1 pod
# or hitting DB load. v1 uses in-JVM Caffeine cache.
# gcloud redis instances create complaints-cache-prod \
#   --size=1 --region=asia-south1 --tier=basic

gcloud storage buckets create gs://complaints-images-prod \
  --location=asia-south1
```

### 3.2 Secrets via Secret Manager
```bash
echo -n "<jwt-secret>"    | gcloud secrets create jwt-secret    --data-file=-
echo -n "<db-password>"   | gcloud secrets create db-password   --data-file=-
echo -n "<msg91-prod>"    | gcloud secrets create msg91-key     --data-file=-
```

### 3.3 Kubernetes Manifests (`k8s/`)
- `deployment.yaml` — 2 min replicas, HPA on CPU 70%
- `service.yaml` — ClusterIP
- `ingress.yaml` — Google-managed SSL + custom domain
- `configmap.yaml` — non-secret env
- `secrets.yaml` — via External Secrets Operator → Secret Manager

### 3.4 CI/CD
- GitHub Actions builds Docker image → pushes to Artifact Registry
- `kubectl apply -f k8s/` on tag `v*`
- Smoke tests post-deploy

---

## 4. Troubleshooting

| Issue | Fix |
|-------|-----|
| `Connection refused` to Postgres in dev | `docker compose ps` — ensure container is up |
| OTP not received in test | Check MSG91 sandbox dashboard for delivery logs |
| `JWT expired` errors on long sessions | Use `/auth/refresh` to get new access token |
| GCS upload fails locally | Ensure `GOOGLE_APPLICATION_CREDENTIALS` env points to SA JSON |
| App OOM in test VM | Add `-Xmx1g` to systemd `ExecStart` |

