# Banco Santo André Card Platform

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

Kafka carries domain events out of the service through a transactional outbox.
Redis carries a short-window throttle and a read-through cache. Neither holds a
source of truth: PostgreSQL answers for every cent.

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

## Validate

Set `JAVA_HOME` to a JDK 17 installation, then run:

```powershell
mvn -B verify
cd web-app
npm test
npm run build
npm audit --audit-level=high
```

The automated test profile uses H2 in PostgreSQL compatibility mode for fast
feedback. H2 passing is not evidence of PostgreSQL equivalence, and it has already
accepted a schema that real PostgreSQL rejected, so `.github/workflows/build.yml`
boots the packaged application against PostgreSQL on every push: Flyway runs the
real migrations and Hibernate validates the mapping against the real column types.

Constraints that the suite relies on are declared on the entity as well as in the
migration. The test schema is generated from the entities, so a constraint that
lives only in SQL would be absent exactly where it is being tested.

## Run locally

```powershell
docker compose up -d postgres redis kafka keycloak
mvn -pl card-service quarkus:dev
```

Swagger UI is available at `http://localhost:8080/q/swagger-ui` in development.
Create a card with `POST /api/v1/cards` and a unique `Idempotency-Key` header.

## Authentication

Every `/api` path requires a bearer token; only `/q/health`, `/q/metrics` and
`/q/openapi` stay public. The access token must carry a `tenant_id` claim, and a
`customer_id` claim for the `customer` role. Requests cannot select a tenant or a
customer: those values are read from the verified token only.

- `customer` — own wallet, own purchases, own cards.
- `admin` — card issuance, interest policy and the portfolio summary.

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

Obtain a token with the direct grant, which is enabled for local development only:

```powershell
$body = @{ grant_type='password'; client_id='card-service'
           username='santoandreadmin'; password='admin1234' }
$token = (Invoke-RestMethod -Method Post `
  "http://localhost:8180/realms/card-platform/protocol/openid-connect/token" `
  -Body $body).access_token
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/wallet/top-ups `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json' -Body '{"amount":100.00}'
```

Run the Angular interface separately:

```powershell
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
