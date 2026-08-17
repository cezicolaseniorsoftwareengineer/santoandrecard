# Banco Santo André Card Platform

Educational credit-card platform MVP built with Java 17 and Quarkus. It is not
third-party source code and does not claim compatibility with proprietary
systems. It must not be exposed outside an isolated local development environment
or receive real customer data.

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

Current idempotency covers retries made after the first transaction commits and
rejects reuse with a different payload. Atomic convergence under concurrent
requests remains blocked until the PostgreSQL integration suite is introduced.

The current installment flow is deliberately simplified: it calculates interest
and debits the simulated wallet total immediately. Monthly invoices, receivables,
minimum payment, delinquency, late fees, reversal and double-entry journal are
specified in `engineering/ADR-002-card-wallet-financial-architecture.md` but are
not implemented yet.

The Angular application currently uses typed local fixtures and its login is still
a UI demonstration. It does not yet perform the authorization-code with PKCE flow,
so the browser journey is not connected to the authenticated API.

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
mvn.cmd -B verify
cd web-app
npm.cmd test
npm.cmd run build
npm.cmd audit --audit-level=high
```

The automated test profile uses H2 in PostgreSQL compatibility mode for fast
feedback. Before production, run the same contracts against PostgreSQL with a
working container runtime; H2 passing is not evidence of PostgreSQL equivalence.

## Run locally

```powershell
docker compose up -d postgres redis kafka keycloak
mvn.cmd -pl card-service quarkus:dev
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

The local realm ships three development users whose passwords are fixtures and
must never be reused:

| User | Password | Role |
| --- | --- | --- |
| `operador.santoandre` | `local-only-admin` | `admin` |
| `cliente.ana` | `local-only-customer` | `customer` |
| `cliente.bruno` | `local-only-customer` | `customer` |

Obtain a token with the direct grant, which is enabled for local development only:

```powershell
$body = @{ grant_type='password'; client_id='card-service'
           username='cliente.ana'; password='local-only-customer' }
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
npm.cmd start
```

The interface is then available at `http://localhost:4200`.

The example Kubernetes secret is intentionally non-operational. Supply the real
`db-password` and `keycloak-admin-password` through a cluster secret manager
before applying the deployment, and create the `keycloak-realm` ConfigMap as
described in `k8s/kustomization.yaml`.

Container image tags must be immutable. `imagePullPolicy: IfNotPresent` makes a
node keep a cached image when a tag is reused, so a redeploy would silently serve
the previous build.
