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
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 1.4 Verify
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Actuator health: <http://localhost:8080/actuator/health>
- Logs: console (OTP mock will print to console)

### 1.5 Stop
```bash
docker compose down              # keep data
docker compose down -v           # wipe data
```

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
- Swagger: `http://<VM-EXTERNAL-IP>:8080/swagger-ui.html`
- Logs: `journalctl -u complaints -f` or GCP Cloud Logging Console

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

