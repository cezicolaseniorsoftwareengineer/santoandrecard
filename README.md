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

Redis and Kafka are provisioned for the next vertical increments. They are not
yet application dependencies: PostgreSQL is the only source of truth in this
increment. The next services are authorization (available-limit reservation,
Redis cache and Kafka events) and ledger (double-entry postings and reconciliation).

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
