# Technical Assessment — Santo André Card, August 2026

Written as a design-review memo: what exists, what is proven, what would be
challenged in a review at a strong engineering organisation, and what the project
is actually differentiated on. Every number below was measured in this repository,
not estimated.

## 1. What the project has

| Area | Measured |
| --- | --- |
| `card-service` production Java | 4,266 lines, hexagonal (`domain` / `application` / `adapter.in` / `adapter.out`) |
| Tests | 2,101 lines, 144 tests, 0 failures |
| REST surface | 19 endpoints across cards, wallet, authorizations, finance |
| Schema | 11 Flyway migrations, 329 lines of SQL, 9 tables |
| Web application | Angular, 1,172 lines of TypeScript, OIDC authorization-code flow |
| Delivery | 4 CI jobs: build+verify, schema boot against real PostgreSQL, web build/audit, dependency vulnerability scan |
| Decision record | ADR-002 (architecture), ADR-003 (card number and PIN), ADR-004 (canonical database) |

Domain implemented: `Card`, `CardNumber`, `CardPin`, `Authorization` with hold and
capture lifecycle, `JournalEntry`/`LedgerAccount` double-entry, `PurchasePlan`,
`InterestCalculator`. Application services for cards, authorizations, ledger,
finance, outbox relay, idempotency retention and purchase backpressure.

Infrastructure: PostgreSQL as source of truth, Redis for throttling and cache with
explicit degrade-open fallbacks, Kafka behind a transactional outbox, Keycloak for
identity, Kubernetes manifests, Docker Compose for local work, Micrometer/Prometheus
metrics and health endpoints.

## 2. Level of the software

The engineering judgement in this codebase is above what its size suggests. The
things that mark it out are structural, not cosmetic:

**Money is modelled the way money has to be modelled.** An append-only double-entry
journal where balances are projections, `NUMERIC(19,2)` with no floating point, and
an invariant that debits equal credits per entry and currency. Most projects at this
scale keep a mutable `balance` column and discover the problem in production.

**Idempotency is enforced by the database, not by a check.** A unique index on
`(tenant_id, idempotency_key)` is the guarantee; losing the race is an expected path
that returns the winner's result. That is the correct shape, and it is tested under
concurrency.

**Failure modes are decided, not inherited.** The rate limiter degrades open with a
written reason; the durable PIN budget is committed outside the failing transaction
precisely so a rollback cannot refund an attacker's attempts; the outbox separates
protecting the source of an event from delivering it. Someone thought about partial
failure before it happened.

**Comments explain judgement, not syntax.** The reason a decision was taken — and
what was rejected — is recorded next to the code that depends on it. That is rare
and it is the strongest single signal in the repository.

**Diagnosis is honest.** The PBKDF2 cost was assumed to be tens of milliseconds and
was measured at 183 ms p50, which changes the risk rather than the latency; the
finding was written into ADR-003 against the author's own earlier claim rather than
quietly dropped.

## 3. What a Principal would challenge in review

These are the questions that would be asked, in the order they would be asked.

**"Show me authentication working end to end."** It cannot be shown today. Every
identity in the test suite is injected through `@TestSecurity`; Keycloak has never
been proven against the canonical database, and the container will not run on the
development machine. Authorization logic is well-placed — checks live in the
application service so no entry point can skip them, and a foreign card is reported
absent rather than forbidden — but *placement is not proof*. This is the largest gap
in the project.

**"Where are the numbers?"** There are none for the deployed system: no latency, no
throughput, no saturation point, no fault-injection run. The only measurement that
exists is the PBKDF2 cost, and it exposed a real problem — roughly a dozen
concurrent PIN reveals saturate every core, and the throttle in front is per card,
so it does not bound that. Reasoned mitigations are recorded and not implemented.

**"What happens when you lose the database?"** Unknown. No backup drill, no restore
test, no RTO or RPO. The canonical instance is a managed Neon database that
development writes to directly.

**"Is tenant isolation enforced or merely intended?"** Application-level only.
`tenant_id` is on every table and derived from the token, and cross-tenant reads are
tested, but PostgreSQL row-level security — named as defence in depth in ADR-002 —
is not implemented. One missing `WHERE tenant_id` in a future query is a cross-tenant
data leak with nothing behind it.

**"Half of ADR-002 is not built."** Billing, statements, installments, payment
allocation, pricing versioning, merchant catalog and admin four-eyes approval are
designed and absent. That is legitimate sequencing, not a defect — but the ADR reads
as a platform while the code is a card and wallet core, and the gap must be stated
rather than implied.

**"Secrets."** A compromised credential is knowingly unrotated in a public
repository's development flow. It is recorded with the conditions that end the
deferral, which is the right way to hold a deliberate risk — and it is still an open
risk.

## 4. Where it sits

Honest placement, by the standard of a strong engineering organisation:

- **Design judgement, failure reasoning, documentation:** senior-to-principal. The
  reasoning about money invariants, idempotency and degraded modes is the real
  article.
- **Proof and operational evidence:** below bar. The gap between *designed
  correctly* and *proven correct* is the entire distance left, and ADR-002's own
  release gates say so.
- **Scope delivered:** a solid card and wallet core, not the platform the ADR
  describes.

The project would pass an architecture review and fail an operational readiness
review. That is a normal and recoverable position for a system at this stage, and it
is exactly what the ADRs already claim: production use stays vetoed.

## 5. What Santo André Card is actually differentiated on

Not the product. A simulated card and wallet with fictitious money is a common
teaching exercise, and nothing in the feature list is novel.

The differentiator is that **the reasoning is auditable**. Three things carry that:

1. **Decisions are recorded with their alternatives and their limits.** ADR-003 does
   not merely say a PIN is hashed; it says why the hash is not the control, why the
   throttle degrades open, why five attempts, and what would void the PCI argument.
   Someone can disagree with a decision here without reverse-engineering it.

2. **The system fails closed and says so.** Authorization checks in the application
   layer, no authoritative state in Redis, correction of history by compensating
   entry rather than update, and a 503 rather than a false 401 when identity cannot
   be verified — each with the reasoning attached.

3. **Claims are separated from proof.** The repository states what is measured, what
   is reasoned, and what is untested. The credential log records a deferred risk as
   a decision rather than hiding it, and a measurement that contradicted an earlier
   claim was written down as such.

For its stated purpose — demonstrating engineering judgement in a financial domain —
that is the right differentiator, and it is more defensible than any feature would
be. The work that would make it stand up beyond demonstration is not more features:
it is Keycloak proven end to end, load and failure numbers, row-level security, and
a restore drill.
