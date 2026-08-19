# Banco Santo André Card Platform

A card issuing and ledger platform, built to show what the engineering behind
money looks like when every claim on this page is attached to the test that
proves it. Banco Santo André is a fictitious institution; the code is original,
carries no third-party branding, and must never receive real customer data.

The centre of it is a double-entry ledger. Balances are not stored figures that
code remembers to update — they are sums over immutable postings, and an entry
whose debits and credits differ cannot be constructed:

```java
// JournalEntry.java — the invariant lives in the domain, not in the service
// and not in the database. An unbalanced entry is not a persistence problem;
// it is not a transaction at all.
BigDecimal debits  = sumOf(postings, DEBIT);
BigDecimal credits = sumOf(postings, CREDIT);
if (debits.compareTo(credits) != 0) {
    throw new UnbalancedTransactionException(debits, credits);
}
```

Everything else in the repository exists to keep that book honest under the
conditions that actually break systems: two requests racing for the same wallet,
a client retrying a payment it never learned the outcome of, a broker that is
down when the money has already committed, a database that disagrees with the
cache in front of it.

## The claims, and where each one is proved

Nothing here rests on the description. Each row names the file that fails if the
guarantee stops holding.

| Guarantee | How it is enforced | What proves it |
| --- | --- | --- |
| An unbalanced transaction cannot exist | Checked in the record's constructor, before anything is written | `JournalEntryTest` |
| Two concurrent purchases cannot overspend one card | Pessimistic row lock on the wallet as a per-customer mutex | `LedgerConcurrencyOnPostgresTest`, **on real PostgreSQL** — H2 has its own idea of what a write lock means |
| A retried payment is charged once | Unique index on `(tenant_id, idempotency_key)`; the loser of the race reads the winner's result | `CardIdempotencyConcurrencyTest`, `MoneyIdempotencyTest` |
| The cached balance never drifts from the book | Reconciliation recomputes from postings and compares | `LedgerReconciliationTest` |
| An event about money is never lost | Transactional outbox; publish then mark, at least once, stable `event-id` | `OutboxRelayTest` |
| A card number is behind a PIN, not behind a session | PBKDF2, 210k iterations, per-card salt, constant-time compare, durable attempt budget | `CardPinTest`, `PinVerificationCostTest`, `cardholder-journey.spec.ts` |
| Identity is never taken from the request | Tenant and customer derived from verified token claims only | `SelfServiceIssuanceTest`, `IdentityProviderUnavailableTest` |
| A slow merchant network refuses rather than debits | Timeout, circuit breaker, fail-closed fallback, HTTP `503` | `AuthorizationLifecycleTest` |
| Load sheds instead of queueing inside a transaction | Bounded admission, HTTP `429` with `Retry-After` | `PurchaseBackpressureGuardTest`, `CardLoadTest` |
| The schema PostgreSQL will accept is the schema that was tested | The packaged app boots against real PostgreSQL in CI, Flyway applies all 12 migrations, Hibernate validates the mapping | `.github/workflows/build.yml`, job `schema on PostgreSQL` |

Coverage is a floor that fails the build rather than a report nobody opens. The
suite currently reaches **84% of instructions and 59% of branches**, and the
gate is set just under that, so erosion fails the next commit rather than this
one. The figures are the measurement, not an aspiration: the floors are raised
when the suite earns it and never lowered to make a build pass.

## Run it, and see it working

```bash
docker compose up -d                    # PostgreSQL, Redis, Kafka, Keycloak
cd card-service && mvn quarkus:dev      # API on :8080, Swagger at /q/swagger-ui
cd ../web-app && npm ci && npm start    # interface on :4420
```

Then open `http://localhost:4420`, create an account through "Criar minha conta"
— the registration happens on Keycloak, and the application never sees a
password — issue a card, fund it and buy something.

To watch what it does while you do it:

```bash
docker compose --profile observability up -d
OTEL_ENABLED=true mvn -pl card-service quarkus:dev
```

Grafana is at `http://localhost:3000` with the dashboard already provisioned —
money moved per operation, refusals by reason, authorizations held against
captured, outbox delivery, and the 99th percentile of the endpoints that carry
money. Traces go to Tempo, and every log line carries the trace id that produced
it. A service can answer every request in ten milliseconds while declining every
one of them, and the JVM gauges will look perfect throughout; these panels are
the ones that would not.

## The whole suite

```bash
mvn -B verify                           # unit, API, PostgreSQL concurrency, coverage floors
cd web-app && npm test                  # component and store tests
npm run e2e                             # browser, real Keycloak, real API
```

`mvn verify` needs Docker for the PostgreSQL tests. `npm run e2e` needs the stack
and the API already running; it starts the front end itself, registers a fresh
cardholder through the real identity provider, and drives the journey a customer
would.

## What it looks like

<img width="1252" alt="Sign-in" src="https://github.com/user-attachments/assets/05425008-7fbe-4456-b196-28dcec41e7cd" /> <img width="1275" alt="Cardholder dashboard" src="https://github.com/user-attachments/assets/3c67e3e4-c19f-42f3-b5d4-a74862568719" />

<img width="1275" alt="Purchase simulation" src="https://github.com/user-attachments/assets/c0eeeb88-dce3-4ee6-8660-ec96404c9168" />

Administrative view

<img width="1276" alt="Portfolio summary" src="https://github.com/user-attachments/assets/03150f39-c943-40f9-aacf-fba6b9df6503" />

Cardholder view

<img width="1270" alt="Card and limit" src="https://github.com/user-attachments/assets/df140951-0cd8-470b-9212-d15ccfa0967d" />

## Architecture in one paragraph

`card-service` is hexagonal: a domain of records and invariants that knows
nothing about Quarkus, application services that own transactions, and adapters
at the edges for REST, JPA, Kafka, Redis and the merchant network. PostgreSQL is
the source of truth and answers for every cent. Kafka carries domain events out
through a transactional outbox. Redis holds a short-window throttle and a
read-through cache, both of which degrade open — losing the entire dataset costs
a window of throttling and one database read, never a cent. The Angular front end
displays only figures that came from an API response, so the interface cannot
disagree with the ledger.

## Delivered increments

- Hexagonal `card-service` with a framework-independent domain model.
- Double-entry ledger with balance enforced in the domain, plus reconciliation
  of the wallet projection against the book.
- Idempotent card creation and money movement, without storing PAN, CVV or
  sensitive authentication data.
- PostgreSQL persistence with twelve versioned Flyway migrations.
- REST/OpenAPI, health endpoints, Prometheus metrics and OpenTelemetry traces.
- Unit, API, concurrency-on-PostgreSQL and browser end-to-end tests, with
  enforced coverage floors.
- Local PostgreSQL, Redis, Kafka and Keycloak through Docker Compose, and a
  provisioned Grafana, Prometheus and Tempo stack behind an `observability`
  profile.
- Hardened Kubernetes workload manifests with probes and resource limits.
- OIDC bearer-token authentication against Keycloak, with `customer` and `admin`
  realm roles and deny-by-default access to every `/api` path.
- Angular interface connected to the API through authorization code with PKCE.
- Tenant and customer identity derived from verified token claims, never from a
  request header or body, with explicit tenant isolation in every query.
- Simulated wallet top-up, card load and merchant purchase flows, plus the
  two-step authorization flow with hold, capture, reversal and expiry.
- Cash and installment quotes with a version-ready monthly interest policy, and
  interest recorded as revenue rather than buried in a net movement.
- Administrative portfolio summary for fictitious balances, principal and interest.
- Explicit purchase backpressure with bounded admission and HTTP `429`.
- Merchant authorization circuit breaker with timeout, fail-closed fallback and
  HTTP `503`.
- Customer self-registration at the identity provider, and self-service card
  issuance with the limit taken from issuer policy and never from the request.
- Full card number revealed only against a four-digit PIN derived with PBKDF2 and
  a per-card salt, behind a persisted attempt budget and a distributed throttle.
- Domain events published to Kafka through a transactional outbox, with
  at-least-once delivery and a stable event id for consumer deduplication.
- Original Banco Santo André visual identity under `assets/brand`.

The installment flow is deliberately simplified: it prices interest and debits
the card immediately. Monthly invoices, receivables, minimum payment,
delinquency, late fees and reversal are specified in
`engineering/ADR-002-card-wallet-financial-architecture.md` and are not
implemented. They are absent from the interface too — showing invented numbers
next to real balances is how an interface stops being trustworthy.

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
| Flyway | 12 migrations | Versioned, auditable schema evolution applied at start. |
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
| Micrometer + Prometheus | via Quarkus | Business outcomes, not just process health: money moved by operation, refusals by reason, authorizations held against captured. Amounts counted in whole centavos, because a counter incremented by 0.1 a thousand times does not read 100. |
| OpenTelemetry + Tempo | 1.62.0 | Distributed traces with the trace id in every log line. Disabled unless an endpoint is configured, so it costs nothing when nothing is collecting. |
| Grafana | 12.2, provisioned | The dashboard ships with the repository and arrives already wired to Prometheus and Tempo. A dashboard someone has to rebuild by hand is a dashboard that does not exist during an incident. |
| JUnit 5 + RestAssured | 3.5.4 | Backend unit and API tests, including overlapping requests and a spent PIN budget. |
| Testcontainers | 2.0.4 | Runs the concurrency tests against real PostgreSQL. The fast suite uses H2, which accepts `PESSIMISTIC_WRITE` and means something else by it; a lock that only holds on H2 does not hold. |
| JaCoCo | 0.8.13, enforced | Coverage floors that fail the build, set just under the 84% instruction and 59% branch coverage the suite reaches. Collected through the Quarkus extension, because the bare agent cannot see a `@QuarkusTest` classloader. |
| Playwright | Chromium | Browser end-to-end journey against the real Keycloak and the real API — a registration redirect, a PKCE exchange and a CORS allow-list only fail where they run. |
| Vitest | 4.1 | Component and store tests, including a boot that must finish while the API never answers. |
| GitHub Actions | build, schema, front end, dependencies, CodeQL | Boots the packaged application against real PostgreSQL on every push, because H2 in the fast profile once accepted a schema PostgreSQL rejected. |
| OSV Scanner | CI job | Scans the resolved dependency tree of both stacks. OSV rather than the NVD feed, because that one needs an API key to be usable at all, and a job that cannot run is a job whose result means nothing. |
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

Every command with the check that proves it worked, for the local stack, the
canonical database and Kubernetes, is in
`engineering/runbook-bring-up.md`. The short path follows.

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

### On Windows, clone somewhere short

```powershell
git clone https://github.com/cezicolaseniorsoftwareengineer/santoandrecard.git C:\dev\santoandrecard
```

The deepest path in the repository is 110 characters. Windows still applies a
260-character limit to the whole path unless long paths are enabled, so cloning
into an already-deep directory fails partway through with `Filename too long`
and leaves a working tree that looks complete and is not. Either clone somewhere
short, as above, or enable long paths once:

```powershell
git config --global core.longpaths true
```

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
cd card-service && mvn quarkus:dev
```

Swagger UI is then at `http://localhost:8080/q/swagger-ui`. Create a card with
`POST /api/v1/cards` and a unique `Idempotency-Key` header.

### 3. Run the interface

```bash
cd web-app
npm ci
npm start
```

The interface is at `http://localhost:4420`. Create an account through "Criar minha
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
mvn -B verify                    # unit, API, PostgreSQL concurrency, enforced coverage
cd web-app
npm test
npm run build
npm audit --audit-level=high
```

`mvn verify` needs Docker: the concurrency tests start a real PostgreSQL through
Testcontainers, and the coverage floors fail the build before the report is
written if the suite has thinned out. The HTML report lands in
`card-service/target/jacoco-report`.

The browser journey is separate, because it needs the whole stack up:

```bash
docker compose up -d
mvn -pl card-service quarkus:dev     # in another shell
cd web-app && npm run e2e
```

It registers a fresh cardholder on the real Keycloak, issues a card, funds it,
buys, reads the statement, and checks that a card number stays masked until the
right PIN is given. Failures keep a trace, a screenshot and a video; passes keep
nothing.

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
./scripts/with-env.sh mvn -f card-service/pom.xml quarkus:dev
```

Windows:

```powershell
Copy-Item .env.example .env   # then fill in HOST, DATABASE, USER, PASSWORD
powershell -File ./scripts/with-env.ps1 java -jar card-service/target/quarkus-app/quarkus-run.jar
powershell -File ./scripts/with-env.ps1 mvn -f card-service/pom.xml quarkus:dev
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

The interface is then available at `http://localhost:4420`.

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
