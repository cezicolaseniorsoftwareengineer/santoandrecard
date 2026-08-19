# Runbook: Bringing the Whole Platform Up

Every command, in order, with the check that proves each step actually worked. A
command that returns without error is not evidence; the check beside it is.

Three ways to run this system. Pick one:

- **A — Local stack.** Everything in Docker on your machine. Start here.
- **B — Canonical database.** Application and Keycloak local, data in Neon (ADR-004).
- **C — Kubernetes.** The manifests, on a cluster.

Where macOS/Linux and Windows differ, both are given. Everything else is identical.

---

## Prerequisites (all paths)

| Tool | Version | Check |
| --- | --- | --- |
| JDK | 17 (Temurin) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js | 22 | `node -v` |
| Docker | Compose v2 | `docker compose version` |

macOS:

```bash
brew install --cask temurin@17
brew install maven node@22
brew install --cask docker      # start Docker Desktop once, then:
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Windows:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven OpenJS.NodeJS.LTS Docker.DockerDesktop
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
```

Clone and enable the commit guard — hooks do not travel with a clone, so this is
not optional:

```bash
git clone https://github.com/cezicolaseniorsoftwareengineer/santoandrecard.git
cd santoandrecard
git config core.hooksPath .githooks
```

Check: `git config core.hooksPath` prints `.githooks`.

---

## Path A — Local stack

### A1. Build and test

```bash
mvn -B verify
```

Check: `Tests run: 144, Failures: 0, Errors: 0`. This step needs no Docker — the
suite runs on H2. If it fails, stop here; nothing downstream will be better.

### A2. Start the infrastructure

```bash
docker compose up -d postgres redis kafka keycloak
docker compose ps
```

Check: all four report `healthy`. Keycloak is the slow one, roughly a minute.
If it never leaves `starting`, read `docker compose logs keycloak`.

### A3. Run the API

```bash
mvn -pl card-service quarkus:dev
```

Check, in a second terminal:

```bash
curl -fsS http://localhost:8080/q/health/ready
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/cards
```

The first prints `"status": "UP"` with the database check `UP`. The second prints
`401` — deny by default, with Keycloak reachable. A `503` there means Keycloak is
not up yet; a `500` means something else is wrong and the log will say so.

Swagger UI: <http://localhost:8080/q/swagger-ui> (development mode only).

### A4. Run the interface

```bash
cd web-app
npm ci
npm start
```

Check: <http://localhost:4200> loads and "Criar minha conta" redirects to Keycloak
on port 8180. That redirect is the proof the OIDC wiring is right.

### A5. Exercise it end to end

Create a customer through the interface, then obtain an admin token and read the
portfolio. macOS/Linux:

```bash
token=$(curl -s -X POST \
  http://localhost:8180/realms/card-platform/protocol/openid-connect/token \
  -d grant_type=password -d client_id=card-service \
  -d username=santoandreadmin -d password=admin1234 | jq -r .access_token)

curl -s http://localhost:8080/api/v1/admin/summary -H "Authorization: Bearer $token"
```

Windows:

```powershell
$body = @{ grant_type='password'; client_id='card-service'
           username='santoandreadmin'; password='admin1234' }
$token = (Invoke-RestMethod -Method Post `
  "http://localhost:8180/realms/card-platform/protocol/openid-connect/token" `
  -Body $body).access_token
Invoke-RestMethod http://localhost:8080/api/v1/admin/summary `
  -Headers @{ Authorization = "Bearer $token" }
```

Check: a JSON summary rather than `401` or `403`. That account is `admin`, so it
reads the portfolio and issues cards; `/api/v1/wallet/*` belongs to a `customer`
and will refuse it. Every money-moving call also needs an `Idempotency-Key` header.

### A6. Stop

```bash
docker compose down       # keeps the data
docker compose down -v    # discards it, which is safe: ADR-004 calls this instance disposable
```

---

## Path B — Against the canonical database

Same as A, except the data lives in Neon and the credential comes from `.env`.

### B1. Create `.env`

```bash
cp .env.example .env      # macOS and Linux
```

```powershell
Copy-Item .env.example .env
```

Fill in `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and the three `KC_DB_*` values.
The Neon connection string splits into three fields: JDBC does not accept
`user:password@` inside the URL. **Both** password fields must be updated together
— updating one leaves identity working while money fails.

### B2. Prove the credential before anything else

```bash
./scripts/with-env.sh mvn -pl card-service quarkus:dev
```

```powershell
powershell -File ./scripts/with-env.ps1 mvn -pl card-service quarkus:dev
```

Check, in the log:

```
Successfully validated 11 migrations
started in N.NNNs. Listening on: http://0.0.0.0:8080
```

`password authentication failed` means the value in `.env` is wrong — most often a
trailing space. `Acquisition timeout` on the first try usually means a suspended
instance waking up; run it again before suspecting the credential.

### B3. Keycloak on the same server

Once, in Neon's SQL editor:

```sql
CREATE SCHEMA IF NOT EXISTS keycloak;
```

Then:

```bash
./scripts/with-env.sh docker compose up -d keycloak
```

```powershell
powershell -File ./scripts/with-env.ps1 docker compose up -d keycloak
```

Check that accounts actually survive the container, which is a claim about
recreation and not restart:

```bash
pwsh ./scripts/verify-keycloak-persistence.ps1            # macOS and Linux
powershell -File ./scripts/verify-keycloak-persistence.ps1  # Windows
```

Check: `PASS: the account created before recreation still authenticates after it.`

### B4. Packaged rather than dev mode

```bash
mvn -pl card-service package
./scripts/with-env.sh java -jar card-service/target/quarkus-app/quarkus-run.jar
```

---

## Path C — Kubernetes

Full detail and the recorded verification are in `k8s/README.md`. The sequence:

```bash
mvn -pl card-service -am clean package
docker build -f card-service/src/main/docker/Dockerfile.jvm \
  -t card-service:0.2.0 card-service

kubectl create namespace card-platform
kubectl -n card-platform create secret generic card-service-secrets \
  --from-literal=db-password="$(openssl rand -base64 24)"

kubectl apply -k k8s/
kubectl -n card-platform rollout status deploy/card-service --timeout=180s
```

Check:

```bash
kubectl -n card-platform get pods
kubectl -n card-platform port-forward svc/card-service 8080:80
curl -fsS http://localhost:8080/q/health/ready
```

Check: `postgres-0`, `keycloak` and `card-service` all `1/1 Running`, and
`/q/health/ready` returns `UP`. On a cluster that does not share Docker's image
store, side-load first: `kind load docker-image card-service:0.2.0`.

---

## Optional: the benchmark

Excluded from the build on purpose — a benchmark that reddens a build on a slow
machine teaches everyone to ignore it.

```bash
mvn -pl card-service test -Dgroups=benchmark -Dtest.excludedGroups=none
```

---

## What "up" does and does not mean here

Paths A and C have been executed and are recorded. Path B is verified as far as the
database goes — connection, migrations at v11, both credentials — and **not** for
Keycloak against the canonical instance: that step has never been run, because
Docker is unavailable on the machine where this was written. Treat B3 as the first
thing to prove, not as a step known to pass.

No load, failure-injection or restore evidence exists for any path. ADR-002's
release gates require all three, and they remain open.
