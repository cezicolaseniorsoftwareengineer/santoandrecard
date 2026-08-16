# Banco Santo André Card Platform

Educational credit-card platform MVP built with Java 17 and Quarkus. It is not
third-party source code and does not claim compatibility with proprietary
systems. It has no authentication or authorization yet and must not be exposed
outside an isolated local development environment or receive real customer data.

## Delivered increments

- Hexagonal `card-service` with a framework-independent domain model.
- Idempotent card creation without storing PAN, CVV or sensitive authentication data.
- PostgreSQL persistence and Flyway schema migration.
- REST/OpenAPI, health endpoints and Prometheus metrics.
- Unit and API integration tests.
- Local PostgreSQL, Redis and Kafka infrastructure through Docker Compose.
- Hardened Kubernetes workload manifest with probes and resource limits.
- Explicit tenant isolation in card, wallet, purchase and administrative queries.
- Simulated wallet top-up and merchant purchase flows.
- Cash and installment quotes with version-ready monthly interest policy.
- Administrative portfolio summary for fictitious balances, principal and interest.
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

The Angular application currently uses typed local fixtures. Its login is a UI
demonstration, not authentication. `X-Tenant-Id` and administrative endpoints must
not be exposed until OIDC, server-derived tenant identity and RBAC are implemented.

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
docker compose up -d postgres redis kafka
mvn.cmd -pl card-service quarkus:dev
```

Swagger UI is available at `http://localhost:8080/q/swagger-ui` in development.
Create a card with `POST /api/v1/cards` and a unique `Idempotency-Key` header.

Run the Angular interface separately:

```powershell
cd web-app
npm.cmd start
```

The interface is then available at `http://localhost:4200`.

The example Kubernetes secret is intentionally non-operational. Supply the real
database password through a cluster secret manager before applying the deployment.
