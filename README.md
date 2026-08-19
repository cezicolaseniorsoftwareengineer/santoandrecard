# Banco Santo André Card Platform

<img width="1252" height="546" alt="image" src="https://github.com/user-attachments/assets/05425008-7fbe-4456-b196-28dcec41e7cd" /> <img width="1275" height="603" alt="image" src="https://github.com/user-attachments/assets/3c67e3e4-c19f-42f3-b5d4-a74862568719" />

<img width="1275" height="612" alt="image" src="https://github.com/user-attachments/assets/c0eeeb88-dce3-4ee6-8660-ec96404c9168" />

ADMIN
<img width="1276" height="598" alt="image" src="https://github.com/user-attachments/assets/03150f39-c943-40f9-aacf-fba6b9df6503" />

USER
<img width="1270" height="598" alt="image" src="https://github.com/user-attachments/assets/df140951-0cd8-470b-9212-d15ccfa0967d" />


Educational credit-card platform MVP built with Java 17 and Quarkus. Banco Santo
André is a fictitious institution created for this project. The work is original:
it derives from no bank's source code, carries no third-party branding and claims
no compatibility with any proprietary system. It must not be exposed outside an
isolated local development environment or receive real customer data.

## Delivered increments

- Hexagonal `card-service` with a framework-independent domain model.
- Idempotent card creation without storing PAN, CVV or sensitive authentication data.
- PostgreSQL persistence and Flyway schema migration.
- REST/OpenAPI, health endpoints and Prometheus metrics.
- Unit and API integration tests.
- Local PostgreSQL, Redis and Kafka infrastructure through Docker Compose.
- Hardened Kubernetes workload manifest with probes and resource limits.
- OIDC bearer-token authentication against Keycloak, with `customer` and `admin`
  realm roles and deny-by-default access to every `/api` path.
- Angular interface connected to the API through authorization code with PKCE,
  reading cards, wallet, statement and the administrative summary.
- Read endpoints for the calling customer: `GET /cards`, `GET /wallet` and
  `GET /purchases`, all scoped to the identity in the token.
- Tenant and customer identity derived from verified token claims, never from a
  request header or body.
- Explicit tenant isolation in card, wallet, purchase and administrative queries.
- Simulated wallet top-up and merchant purchase flows.
- Cash and installment quotes with version-ready monthly interest policy.
- Administrative portfolio summary for fictitious balances, principal and interest.
- Explicit purchase backpressure with bounded concurrent admission and HTTP `429` rejection.
- Merchant authorization circuit breaker with timeout, fail-closed fallback and HTTP `503`.
- Original responsive Angular interface with customer and admin journeys.
- Original Banco Santo André visual identity under `assets/brand`.
- Customer self-registration at the identity provider, with the realm's default
  role granting access to the cardholder dashboard on first sign-in.
- Self-service card issuance, available at any hour, with the issuing limit taken
  from issuer policy and never from the request.
- Santo André Card Platinum as a product on the card itself, rendered at the
  ID-1 ratio of a physical card.
- Full card number revealed only against a four-digit PIN derived with PBKDF2 and
  a per-card salt, behind a persisted attempt budget and a distributed throttle.
- Prepaid card balance funded by a ledger-backed transfer from the wallet.
- Domain events published to Kafka through a transactional outbox, with
  at-least-once delivery and a stable event id for consumer deduplication.

Kafka carries domain events out of the service through a transactional outbox.
Redis carries a short-window throttle and a read-through cache. Neither holds a
source of truth: PostgreSQL answers for every cent.

## Technology

Every dependency below is used by code in this repository. Nothing is declared
for appearance.

| Technology | Version | What it settles |
| --- | --- | --- |
| Java | 17 LTS | Immutable records for aggregates and `BigDecimal` for every amount; no `double` or `float` exists in the codebase, because binary floating point does not represent centavos. |
| Quarkus | 3.33.3 | Fast-starting, low-memory runtime suited to containers, with the domain kept independent of it. |
| PostgreSQL | 17 | The single source of truth. A unique index enforces idempotency under concurrency, `CHECK` constraints restrict ledger accounts and entry kinds, and a pessimistic row lock serialises debits on one wallet. |
| Double-entry ledger | own domain | Debit equals credit is enforced in the entry's constructor, so an unbalanced transaction cannot exist. Reconciliation compares the projection against the book. |
| Hibernate ORM + Panache | via Quarkus | Mapping validated against the real schema at boot; the application generates no DDL and refuses to start on divergence. |
| Flyway | 8 migrations | Versioned, auditable schema evolution applied at start. |
| Apache Kafka | 4.1.1 (KRaft) | Domain events leave the service through a transactional outbox, at least once, keyed by customer — the only scope in which Kafka promises ordering. |
| Redis | 8 | Distributed PIN throttle and a read-through cache for the administrative summary. Holds no source of truth and degrades open when unreachable. |
| Quarkus Scheduler | via Quarkus | Drains the outbox, releases expired authorization holds and prunes idempotency records, all outside the request path — a slow broker cannot fail a payment already committed to the ledger. |
| Keycloak + OIDC | 26.4 | Bearer-token authentication, deny by default on `/api`, identity from verified claims, and self-service registration the application never sees a password for. |
| PBKDF2 (JDK) | 210k iterations | Card PIN derived with a per-card salt and compared in constant time. |
| SmallRye Fault Tolerance | via Quarkus | Timeout and circuit breaker on merchant authorization with a fail-closed fallback; bounded admission rejects with `429` instead of queueing a financial request inside an open transaction. |
| Hibernate Validator | Jakarta Validation | Input validated at the edge, with the same constraints also declared on the entity and in the migration. |
| Angular | 21 (standalone, signals) | Reactive state without an external state library; every figure shown comes from an API response, so the interface cannot disagree with the ledger. |
| TypeScript | 5.9 | Typed API contracts and a pt-BR currency field that reads digits as centavos while the bound model stays numeric. |
| Docker + Compose | — | Reproducible local stack with health checks and a pinned project name. |
| Kubernetes | manifests + Ingress | Deployments, StatefulSets, Ingress and a PodDisruptionBudget, with `runAsNonRoot`, `readOnlyRootFilesystem`, `drop: ALL`, seccomp, resource limits and explicit probe timeouts. |
| nginx (unprivileged) | front-end image | Serves the built interface as a non-root user, with endpoints injected at runtime so one image serves every environment. |
| Micrometer + Prometheus | via Quarkus | Runtime and fault-tolerance metrics, alongside health endpoints and a published OpenAPI contract. |
| JUnit 5 + RestAssured | 3.5.4 | Backend unit and API tests, including overlapping requests and a spent PIN budget. |
| Vitest | 4.1 | Front-end tests, including a boot that must finish while the API never answers. |
| GitHub Actions | 5 jobs | Boots the packaged application against real PostgreSQL on every push, because H2 in the fast profile once accepted a schema PostgreSQL rejected. |
| OWASP Dependency-Check | CI job | Scans backend dependencies for known vulnerabilities, failing on CVSS 7 and above. Dependencies are the part of the attack surface nobody writes. |
| CodeQL | CI job | Static analysis of the Java and TypeScript this repository writes, which dependency scanning cannot see. |

## What Redis is allowed to hold

Nothing worth keeping. Every key expires, and losing the whole dataset costs a
window of throttling and one database read — never a cent, an identity or an
authorisation decision.

It does two jobs. It throttles PIN reveals per card, which is not the same
control as the durable five-attempt budget on the card: that budget is the
authority and survives restarts, but five attempts is no defence if an attacker
can spend all five in a burst, across replicas, before any of them writes a row.
The window is what makes the budget cost an attacker time. And it caches the
administrative summary for ten seconds — an aggregate over every wallet and
purchase of a tenant, which a polling dashboard should not make the database
repeat for a figure that barely moved.

Both degrade to their absent behaviour when Redis is unreachable: the throttle
allows and the cache misses. That is deliberate. Neither holds a guarantee, so
taking payments down because a cache is missing would trade a small risk for a
certain outage. The same behaviour is what a deployment with `card.redis.enabled`
switched off gets, through default beans rather than through a failure — which is
also how the fast test profile runs with no server to reach.

The summary is serialised positionally by hand. Cached bytes outlive the
deployment that wrote them, and reflecting over a class would let a renamed field
read a value into the wrong column.

## Events and the outbox

An event must be published exactly when the state change it describes commits.
Publishing inside the transaction emits events for work that later rolls back;
publishing after it loses events when the process dies in between. Neither is
acceptable for money, so the intent to publish is written to `outbox_events` in
the same transaction as the change, and a scheduled relay turns intent into
delivery.

Delivery is **at least once**, by decision rather than by limitation. The relay
publishes and then marks; marking first would lose the event on a failure that
has not happened yet. Losing an event about money is unacceptable and receiving
one twice is merely inconvenient, so **consumers must be idempotent** and every
record carries a stable `event-id` header for exactly that purpose.

This is observable rather than theoretical. On the first run against a broker
that had not yet created the topic, the send timed out after the broker had in
fact written the record; the relay retried and the topic ended up with the same
`event-id` twice. A consumer that does not dedupe would have counted that top-up
twice.

Records are keyed by customer, which is the only scope in which Kafka promises
ordering. The producer runs with `acks=all`, `enable.idempotence=true` and one
in-flight request per connection, so a producer-side retry cannot duplicate or
reorder within a partition.

After ten failed attempts an event stays unpublished and visible instead of
being discarded: an event about money is an operator's problem, not garbage.

Every operation that moves money requires an `Idempotency-Key`: top-up, card
load and purchase, alongside card issuance. A client whose request timed out
cannot tell a lost request from a lost response, and for a payment those are
opposite situations — only the server knows which happened. A replayed key
returns the original outcome; the same key replayed with a different body is a
client defect and is refused with `409`. The key is scoped to its operation,
because one key used on a top-up and on a purchase describes two intents.

Idempotency holds under concurrency, not only for sequential retries. Checking for
an existing key and then inserting cannot be atomic on its own, so the unique
index on `(tenant_id, idempotency_key)` is what enforces the guarantee: a caller
that loses the race reads the winning card and returns it. Reusing a key with a
different payload is still refused with `409`. `CardIdempotencyConcurrencyTest`
fires overlapping requests and fails if either property is lost.

The current installment flow is deliberately simplified: it calculates interest
and debits the simulated wallet total immediately. Monthly invoices, receivables,
minimum payment, delinquency, late fees, reversal and double-entry journal are
specified in `engineering/ADR-002-card-wallet-financial-architecture.md` but are
not implemented yet.

The Angular application signs in through Keycloak with the authorization code flow
and PKCE, and every figure it displays comes from an API response. It holds the
access token in memory and the refresh token in `sessionStorage`, so closing the
tab ends the session. Invoices, receivables and delinquency were removed from the
interface: they have no backend and showing invented numbers next to real balances
would make the interface untrustworthy.

## Resilience controls

Purchase admission is bounded by `CARD_PURCHASE_MAX_CONCURRENT` (default `32`).
When all permits are in use, new requests are rejected immediately with
`PURCHASE_BACKPRESSURE`, HTTP `429` and `Retry-After: 1`; financial requests are
not silently queued inside an open database transaction.

Merchant authorization is protected by SmallRye Fault Tolerance with a 500 ms
timeout and a circuit breaker over a four-request window. A 50% failure ratio
opens the circuit for five seconds; two successful half-open calls close it.
Timeouts, network failures and an open circuit use a fail-closed fallback,
returning `MERCHANT_AUTHORIZATION_UNAVAILABLE`, HTTP `503` and `Retry-After: 5`
without debiting the wallet. Fault-tolerance metrics are exported through the
existing Micrometer/Prometheus integration.

## Getting started

Everything below runs the same on macOS, Linux and Windows. Where a command
genuinely differs, both are given: the shell script for macOS and Linux, the
PowerShell script for Windows. They are equivalent — same contract, same output.

### Enable the commit guard first

One command, once per clone:

```bash
git config core.hooksPath .githooks
```

`.githooks/pre-commit` refuses to commit `.env`, anything under `secrets/`, and
the operator prompt file. `.gitignore` already lists them, but an ignore rule is a
convention: it is one line, and deleting it is an ordinary-looking commit — which
has already happened once here. The hook inspects what is actually staged, so it
holds even when the rule is gone or `git add -f` was used.

### Prerequisites

| Tool | Version | Checked with |
| --- | --- | --- |
| JDK | 17 (Temurin, as in CI) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js | 22 | `node -v` |
| Docker | with Compose v2 | `docker compose version` |

`JAVA_HOME` must point at the JDK 17 installation. Docker is needed only for the
local stack; the test suite runs without it.

macOS, with Homebrew:

```bash
brew install --cask temurin@17
brew install maven node@22
brew install --cask docker    # then start Docker Desktop once
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Windows, with winget:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven OpenJS.NodeJS.LTS Docker.DockerDesktop
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
```

### 1. Start the infrastructure

Identical on both systems:

```bash
docker compose up -d postgres redis kafka keycloak
```

Wait for the containers to report healthy with `docker compose ps`. Keycloak takes
the longest; the application will not authenticate anyone until it is up.

### 2. Run the API

```bash
mvn -pl card-service quarkus:dev
```

Swagger UI is then at `http://localhost:8080/q/swagger-ui`. Create a card with
`POST /api/v1/cards` and a unique `Idempotency-Key` header.

### 3. Run the interface

```bash
cd web-app
npm ci
npm start
```

The interface is at `http://localhost:4200`. Create an account through "Criar minha
conta", which enters Keycloak's registration screen — the application never sees a
password.

### 4. Stop

```bash
docker compose down          # keeps the data
docker compose down -v       # discards it, which is safe: see ADR-004
```

## Validate

Set `JAVA_HOME` to a JDK 17 installation, then run — identical on both systems:

```bash
mvn -B verify
cd web-app
npm test
npm run build
npm audit --audit-level=high
```

The benchmarks are excluded from the build, because one that reddens a build on a
slow machine teaches everyone to ignore it. Run them deliberately:

```bash
mvn -pl card-service test -Dgroups=benchmark -Dtest.excludedGroups=none
```

The automated test profile uses H2 in PostgreSQL compatibility mode for fast
feedback. H2 passing is not evidence of PostgreSQL equivalence, and it has already
accepted a schema that real PostgreSQL rejected, so `.github/workflows/build.yml`
boots the packaged application against PostgreSQL on every push: Flyway runs the
real migrations and Hibernate validates the mapping against the real column types.

Constraints that the suite relies on are declared on the entity as well as in the
migration. The test schema is generated from the entities, so a constraint that
lives only in SQL would be absent exactly where it is being tested.

## Which database is the real one

Three PostgreSQL instances exist, and only one of them is the platform. The managed
Neon database is canonical: money, identity and the demonstration's history live
there, and it is what reconciliation and support answer against. The Compose volume
and the Kubernetes PVC are disposable by decision — the first is the local feedback
loop, the second proves the manifests deploy — and deleting either costs nothing.
Nothing is replicated or reconciled between the three; they are unrelated histories,
not copies. ADR-004 records why, and what that makes the credential worth.

## Pointing at a managed PostgreSQL

The datasource is entirely environment driven, so a hosted database needs no
code change — only three variables. Flyway runs the migrations on first start,
so an empty database becomes the full schema by itself.

Copy `.env.example` to `.env`, fill it in, and run anything through it. Git ignores
`.env`, the values are set for one child process, and only the variable names are
printed — a credential typed at a prompt lives on in shell history, which is the
same mistake in a slower form.

macOS and Linux:

```bash
cp .env.example .env          # then fill in HOST, DATABASE, USER, PASSWORD
./scripts/with-env.sh java -jar card-service/target/quarkus-app/quarkus-run.jar
./scripts/with-env.sh mvn -pl card-service quarkus:dev
```

Windows:

```powershell
Copy-Item .env.example .env   # then fill in HOST, DATABASE, USER, PASSWORD
powershell -File ./scripts/with-env.ps1 java -jar card-service/target/quarkus-app/quarkus-run.jar
powershell -File ./scripts/with-env.ps1 mvn -pl card-service quarkus:dev
```

A managed instance that has been idle may suspend. The pool gives up after
`DB_ACQUISITION_TIMEOUT` — five seconds by default — and Flyway then fails the
start outright, which is what a cold Neon endpoint looks like: one failed boot,
then a clean one. Raise the variable if a first start has to survive it.

`sslmode=require` is not optional. The connection leaves the machine, and
without it the driver will happily send the password in the clear; every managed
provider offers TLS and most enforce it.

Two things change when the database stops being local. Connections become
scarce — hosted plans cap them per project, and the pool is sized here rather
than inherited so two replicas cannot exhaust a small plan between them. And
idle connections are dropped from the far side, which is why connections are
validated in the background: the alternative is the first query after a quiet
period failing on a connection the pool still believes is open.

The credentials in `compose.yaml` and in this document are local fixtures, and
they are published in the clear deliberately: they open disposable instances, which
is what makes them harmless. They must never be used on a database reachable from
the internet. The canonical credential goes in the environment of the process that
needs it and nowhere else — not a manifest, not an issue, not a chat. One that has
been anywhere else is compromised on that fact alone and is rotated, not assessed;
see `engineering/credential-rotation-log.md`.

## What survives a restart

| Data | Where it lives | Survives |
| --- | --- | --- |
| Cards, wallets, purchases, authorizations, ledger, outbox, idempotency | PostgreSQL | Restart, recreation, host reboot |
| PIN attempt counters | `cards.pin_attempts` | Restart — deliberately, or restarting the service would hand an attacker a fresh budget |
| Accounts, sessions, realm configuration | PostgreSQL, through Keycloak | Restart and recreation |
| Kafka topics | `kafka-data` volume | Restart and recreation |
| Throttle counters and cached summary | Redis, all keys expiring | Nothing, by design |

Keycloak stores its accounts in a database rather than inside the container.
With the development default they survived a restart but not a recreation, and
the realm was then reimported over them — every self-registered customer
silently gone, which is exactly the failure a demonstration finds for you.

Kafka now keeps its log in a volume, matching the Kubernetes manifest that
already had one. The outbox protects the *source* of an event, not its delivery:
once a row is marked published it is never sent again, so losing the log loses
every event no consumer had read yet.

Idempotency records are pruned by age. Retention is thirty days — far longer
than any client would retry, because forgetting a key while a caller still holds
it turns their retry into a second payment. The sweep deletes in bounded
batches, so a payment never waits behind housekeeping.

### Pointing Keycloak at the same managed database

Neon provides a single database, so Keycloak takes a schema inside it rather
than a database of its own. It creates its tables but never the schema, so that
has to exist first:

```sql
CREATE SCHEMA IF NOT EXISTS keycloak;
```

The `KC_DB_*` variables live in the same `.env`:

```bash
./scripts/with-env.sh docker compose up -d keycloak            # macOS and Linux
```

```powershell
powershell -File ./scripts/with-env.ps1 docker compose up -d keycloak   # Windows
```

That accounts survive the container is a claim about recreation, not restart, so it
is checked rather than assumed:

```powershell
powershell -File ./scripts/verify-keycloak-persistence.ps1
```

On macOS and Linux, run it with PowerShell 7 (`brew install --cask powershell`):

```bash
pwsh ./scripts/verify-keycloak-persistence.ps1
```

It creates an account, destroys and rebuilds the container, and requires that same
account to authenticate afterwards.

Identity and money share a server for convenience here, never a schema.

## Authentication

Every `/api` path requires a bearer token; only `/q/health`, `/q/metrics` and
`/q/openapi` stay public. The access token must carry a `tenant_id` claim, and a
`customer_id` claim for the `customer` role. Requests cannot select a tenant or a
customer: those values are read from the verified token only.

- `customer` — own wallet, own purchases, own cards.
- `admin` — card issuance, interest policy and the portfolio summary.

While the provider is unreachable the API answers `503` with `Retry-After`, not
`500` and not `401`. The request is still refused — authentication fails closed —
but nothing verified a credential, so claiming the credential failed would be a
lie, and claiming the request was malformed stops a client from retrying one that
would now succeed.

## Accounts

Customers create their own account. The interface offers "Criar minha conta",
which enters the same authorization code flow at the provider's registration
screen, so the application never sees or stores a password. Self-registration is
enabled on the realm and every new account inherits the realm default role
`customer`, which is what grants access to the cardholder dashboard.

Identity claims are issued by the provider, not chosen by the caller:

- `tenant_id` — a hardcoded claim mapper; this deployment is a single tenant.
- `customer_id` — mapped from the account's own provider identifier, so a
  self-registered user gets a stable, unforgeable customer identity with no
  manual provisioning step.

The realm ships one administrative account. Its password is a local fixture and
must never be reused anywhere else:

| User | Password | Role |
| --- | --- | --- |
| `santoandreadmin` | `admin1234` | `admin` |

Obtain a token with the direct grant, which is enabled for local development only.
Note the role: this account is `admin`, so it reads the portfolio and issues cards,
and it is refused on `/api/v1/wallet/*` — those belong to a `customer`. Every
money-moving call additionally requires an `Idempotency-Key` header.

macOS and Linux, with `curl` and `jq`:

```bash
token=$(curl -s -X POST   http://localhost:8180/realms/card-platform/protocol/openid-connect/token   -d grant_type=password -d client_id=card-service   -d username=santoandreadmin -d password=admin1234 | jq -r .access_token)

# The portfolio summary is an admin read, which is what this token can do.
curl -s http://localhost:8080/api/v1/admin/summary   -H "Authorization: Bearer $token"
```

Windows:

```powershell
$body = @{ grant_type='password'; client_id='card-service'
           username='santoandreadmin'; password='admin1234' }
$token = (Invoke-RestMethod -Method Post `
  "http://localhost:8180/realms/card-platform/protocol/openid-connect/token" `
  -Body $body).access_token
# The portfolio summary is an admin read, which is what this token can do.
Invoke-RestMethod http://localhost:8080/api/v1/admin/summary `
  -Headers @{ Authorization = "Bearer $token" }
```

Run the Angular interface separately, identically on both systems:

```bash
cd web-app
npm start
```

The interface is then available at `http://localhost:4200`.

The example Kubernetes secret is intentionally non-operational. Supply the real
`db-password` and `keycloak-admin-password` through a cluster secret manager
before applying the deployment, and create the `keycloak-realm` ConfigMap as
described in `k8s/kustomization.yaml`.

Container image tags must be immutable. `imagePullPolicy: IfNotPresent` makes a
node keep a cached image when a tag is reused, so a redeploy would silently serve
the previous build.

## Credits

Developed by **Cezi Cola, Software Engineer** — **Bio Code Technology**.

Banco Santo André is a fictitious institution created for this project. The work
is original, derives from no bank's source code, carries no third-party branding
and claims no compatibility with any proprietary system.
