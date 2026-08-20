# Technical Review — Banco Santo André Card Platform

Date: 2026-08-20 · Reviewer: Cezi Cola, Principal Software Engineer

Scope: full repository — domain, application, adapters, database, front end,
infrastructure, pipeline. Read-only review; no build was executed in this pass,
so every figure below is either measured from the source or quoted from the
project's own gate configuration and labelled as such.

## 1. Measured shape of the codebase

| Area | Measurement | Method |
| --- | --- | --- |
| Backend main | 88 Java files, 5,484 lines | `find`/`wc` over `card-service/src/main/java` |
| Backend tests | 31 files, 3,399 lines | same over `src/test` |
| Test-to-code ratio | 0.62 lines of test per line of production code | derived |
| Schema | 12 Flyway migrations, forward-only | `resources/db/migration` |
| Front end | 41 files, ~3,519 lines (810 of them CSS) | `find`/`wc` over `web-app/src`, `web-app/e2e` |
| Front-end unit tests | 40 assertions across 7 spec files | `grep -c "it("` |
| CI | 8 jobs on every push, 3 workflows | `.github/workflows` |
| Kubernetes | 9 manifests + kustomization, schema-validated in CI | `k8s/` |
| Coverage gate | instructions >= 0.82, branches >= 0.57, domain >= 0.82 | root `pom.xml` (gate value, not a run) |

## 2. Technology inventory

**Backend.** Java 17 LTS, Quarkus 3.33.3, Jakarta EE (CDI, JAX-RS/RESTEasy
Reactive, JPA/Hibernate ORM, Bean Validation, JTA), Hibernate with
`schema-management.strategy=validate` so the code can never quietly reshape the
database, Flyway migrate-at-start, PostgreSQL 17, Redis 8 (Quarkus Redis
client), Apache Kafka 4.1 through a transactional outbox, SmallRye
OpenAPI/Swagger UI, SmallRye Health, SmallRye Fault Tolerance (timeout, circuit
breaker, fallback), Quarkus Scheduler, Micrometer + Prometheus registry,
OpenTelemetry 1.62 traces with trace and span ids injected into every log line,
Quarkus OIDC bearer-token resource server, PBKDF2WithHmacSHA256 for PIN
derivation, `BigDecimal` money throughout with `NUMERIC(19,2)` and scale
enforced in constructors.

**Front end.** Angular 21 standalone components, signals and `computed` as the
only state mechanism, `ChangeDetectionStrategy.OnPush` everywhere,
`HttpInterceptorFn` functional interceptors (bearer token, idempotency key), a
custom BRL currency directive, Vitest + jsdom for units, Playwright for the
browser journey, TypeScript 5.9, nginx-unprivileged as the runtime image.

**Identity.** Keycloak 26.4, realm imported as code, OIDC authorization code
flow with PKCE implemented by hand in `auth.service.ts` — no adapter library —
access token in memory only, refresh token in `sessionStorage`, never
`localStorage`. Registration happens on the identity provider, so the
application never receives a password.

**Infrastructure and delivery.** Docker Compose with an `observability` profile,
Kubernetes manifests with Kustomize, GHCR images built by buildx and tagged from
`docker/metadata-action`, PodDisruptionBudget, topology spread constraints,
readiness and liveness probes, requests and limits on every container,
`runAsNonRoot` + `drop: ["ALL"]` + `readOnlyRootFilesystem` on the service pod,
Prometheus, Tempo, Grafana with a provisioned dashboard, CodeQL over both
languages with `security-and-quality`, OSV-Scanner over the resolved dependency
tree, kubeconform in strict mode, and a CI step that asks GHCR whether every
image tag the manifests reference actually exists.

**Engineering artefacts.** Four ADRs, two runbooks, a credential-rotation log, a
hash-chained evidence ledger, and a decision/benchmark gate record set.

## 3. Excellent

1. **The ledger is a real double-entry ledger.** `JournalEntry` rejects an
   unbalanced entry in the record constructor: at least two postings, debits
   equal to credits, positive total, per-customer accounts required to carry a
   customer id. The invariant is unreachable from the outside — there is no code
   path that writes postings without constructing the record first.
2. **Balances are derived, and the derivation is audited.**
   `LedgerService.balanceOf` sums postings on the account's normal side;
   `WalletEntity.balance` exists only as a lockable projection, and
   `reconcileWallets` recomputes from the book and reports every divergence.
   `unbalancedTransactions` proves the book is internally coherent. Most
   portfolio projects store a balance column and call it a ledger.
3. **Concurrency is tested where it actually breaks.**
   `LedgerConcurrencyOnPostgresTest` runs against real PostgreSQL because H2's
   idea of a pessimistic write lock is not PostgreSQL's — the comment in the CI
   file says exactly that, and it is correct. `PESSIMISTIC_WRITE` on the wallet
   row is used as a per-customer mutex, consistently, in `FinanceService`,
   `AuthorizationService` and `BillingService`.
4. **Idempotency is a database guarantee, not a cache lookup.** Unique index on
   `(tenant_id, idempotency_key)`; the loser of the race reads the winner's
   stored result rather than retrying. Retention is bounded and pruned in
   batches, and the window is deliberately longer than any plausible client
   retry.
5. **The outbox is correct.** Recorded in the same transaction as the money,
   drained on a schedule outside the request path, publish-then-mark, stable
   `event-id` for at-least-once with downstream deduplication, and after
   `max-attempts` the event stays visible instead of being discarded. The
   comment "an event about money is an operator's problem, not garbage" is the
   right policy.
6. **Identity is never taken from the request.** Tenant and customer come from
   verified token claims only; `MissingIdentityClaimException` fails closed. The
   self-service credit limit is issuer policy in configuration, with the comment
   "a limit chosen by the applicant is not a limit". `/api/*` is deny-by-default
   with only the operational endpoints public, and the `OPTIONS` exclusion is
   justified from the CORS specification rather than copied from a forum.
7. **The CI pipeline tests things unit tests structurally cannot.** The packaged
   application boots against real PostgreSQL so Flyway applies all 12 migrations
   and Hibernate validates the mapping; the browser journey runs against a real
   Keycloak; the manifests are rendered, strict-validated, and their image
   references resolved against the registry. That last job exists because a
   hand-written tag drifted in four places — and the comment says so.
8. **Failure modes are chosen, documented, and asymmetric in the right
   direction.** Merchant gateway: timeout, circuit breaker, fail-closed
   fallback, HTTP 503 — so a slow network refuses rather than debits. Redis:
   fails open, and is deliberately excluded from readiness because pulling a pod
   that can still take payments is worse than a slower cache, with the reasoning
   written down. Purchases: bounded admission with HTTP 429 and `Retry-After`,
   so load sheds instead of queueing inside a transaction.
9. **The domain package has zero outward imports.** Measured: no `import` from
   `application` or `adapter` anywhere in `domain/`. The money rules compile
   without a framework on the classpath.
10. **The comments explain decisions, not syntax.** The OpenTelemetry BOM comment
    records a real failure (API raised alone, SDK left behind, breaks at
    startup), why a BOM fixed it, and why declaration order matters. The
    coverage-floor comment states that floors are raised when earned and never
    lowered to make a build pass. This is the register of a senior engineer
    writing for whoever is on call next.

## 4. Good

- **Hexagonal structure with real ports.** `CardRepository`, `EventPublisher`,
  `MerchantAuthorizationPort`, `RateLimiter`, `SummaryCache` — and the
  absent-Redis behaviour is a `@DefaultBean` producer, so running without Redis
  is a configuration choice rather than a wiring failure.
- **PIN handling.** PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte per-card
  salt, 256-bit key, `MessageDigest.isEqual` for constant-time comparison,
  `spec.clearPassword()` in a `finally`. The class documents honestly that with
  four digits the derivation protects a stolen database, not a live card, and
  that the durable attempt budget is the real control — which is exactly right,
  and rarer than the iteration count.
- **`CardNumber`.** Luhn-valid, generated from a fictitious BIN no network
  routes, with a comment stating plainly that a real issuer would vault the PAN
  outside this service and hold a token here. Scope stated instead of
  overclaimed.
- **Multi-tenancy discipline.** Every repository query and every `find` is
  tenant-scoped; `BillingService.pay` returns the same "not found" for a
  statement that is absent and one that belongs to somebody else, with the
  comment explaining that distinguishing them is itself a leak.
- **Configuration.** Every operational value is an environment variable with a
  sane default and a comment explaining the number. Pool sizing, acquisition
  timeout, background validation, leak detection — chosen for a managed database
  rather than inherited from the framework default.
- **Front-end architecture.** Signals-only state, OnPush throughout, one shell
  that owns navigation, boot and error presentation while the screens stay
  ignorant of what a failure means. The idempotency interceptor puts the retry
  guarantee in one place instead of at every call site.
- **Operational readiness.** Runbooks for bring-up and credential rotation, a
  rotation log, and provisioned Grafana dashboards showing money moved, refusals
  by reason, holds against captures, outbox delivery and p99 on the money
  endpoints — with the observation that a service can answer every request in
  ten milliseconds while declining all of them, and that JVM gauges would look
  perfect throughout. That is the difference between having dashboards and
  understanding monitoring.
- **Container hygiene.** Unprivileged nginx on 8080 so the pod is admissible
  under the restricted Pod Security Standard with a read-only root filesystem;
  cache policy reasoned per path (`config.json` no-store, so a pod cannot serve
  the previous environment's issuer after a redeploy).

## 5. Adequate

- **Branch coverage floor at 0.57.** Instruction coverage around 0.84 with
  branch coverage around 0.59 means a meaningful share of decision points is
  unexercised. On money paths the branch is the interesting part. The floor is
  honest about where it stands; it is still the weakest number on the page.
- **`FinanceService` does six jobs in 425 lines** — wallet top-up, card load,
  purchase, quoting, interest policy, admin summary. Readable today; it is the
  file two people will edit at once.
- **The application layer imports persistence entities** (19 imports across 9
  files). Pragmatic at this size and the domain stays clean, but the ports
  pattern is applied to Redis, Kafka and the merchant gateway and not to the
  database, which is an inconsistency a reviewer will ask about.
- **No architecture test.** The boundary the layout implies holds today by
  discipline alone. ArchUnit would make it a build failure instead of a review
  finding.
- **Single Kafka, PostgreSQL, Redis and Keycloak replica in `k8s/`.** Correct for
  a demonstration cluster, and the service itself runs two replicas with a PDB.
  Worth one line in the README saying the data tier is deliberately single-node.
- **Front-end test depth.** 40 unit assertions and one Playwright journey cover
  the critical path, but the admin dashboard, statement screen and purchase
  simulator have no unit specs.
- **Accessibility.** ARIA attributes appear in 4 of 9 templates; admin dashboard,
  invoices, statement and purchase simulator have none. No focus management on
  the PIN dialog is visible in markup, and there is no automated a11y check in
  the pipeline.

## 6. Weak

1. **`npm run lint` does not lint.** It is `ng build --configuration
   development`. There is no ESLint configuration in the repository and no lint
   job in CI, so the front end has no static correctness gate at all — while the
   backend has CodeQL, OSV and three coverage floors. This is the clearest
   asymmetry in the project, and it is a five-minute fix.
2. **No Content-Security-Policy and no Strict-Transport-Security.** `nginx.conf`
   sets `X-Content-Type-Options`, `X-Frame-Options` and `Referrer-Policy`, then
   stops. For an application holding a bearer token in JavaScript memory, CSP is
   the control that limits what an injected script can do with it, and HSTS is
   what stops the first request from being downgraded. The ingress adds neither.
3. **No row-level security in PostgreSQL.** Tenant isolation is enforced
   entirely in application code, and enforced consistently — but it is one
   forgotten `where tenant_id = :tenantId` away from a cross-tenant read, with
   nothing underneath to catch it. RLS with a session variable would make the
   database refuse the query the code forgot to scope.
4. **No SBOM and no image signing in `release.yml`.** The pipeline builds and
   pushes to GHCR with metadata-driven tags, and `build.yml` scans dependencies —
   but nothing downstream can verify what is inside the image or who produced it.
   For a project whose thesis is auditable evidence, this is the gap that stands
   out.
5. **No router in the front end.** Navigation is a `signal<CustomerView>` in the
   shell. The consequences are concrete: no deep links, no working back button,
   no route guards, no lazy loading — and `nginx.conf` already carries a
   `try_files` rule whose comment mentions `/extrato`, a route that does not
   exist. The infrastructure is ready for routing the application does not have.
6. **No load or soak evidence in CI.** `CardLoadTest` and
   `PurchaseBackpressureGuardTest` prove the guard rejects, and `benchmark` is an
   excluded JUnit tag, so no throughput or latency figure is produced by any run.
   The limit of 32 concurrent purchases is therefore an assumption, not a
   measurement.
7. **`README.md` opens with eight raw screenshots before the first sentence.**
   The prose underneath is the strongest technical writing in the repository, and
   a reviewer meets it after two screens of scrolling.

## 7. Verdict

This is not a portfolio project with a ledger in it. It is a card-issuing and
ledger platform whose money invariants are enforced in the type system, proved
under concurrency against the real database, and re-checked by a reconciliation
that would fail loudly if a projection ever drifted from the book. The
difference between this and the rest of the field is not the stack — anyone can
list Quarkus, Kafka and Kubernetes — it is that every guarantee on the front
page names the test that fails when the guarantee stops holding, those tests
exist, and they test the hard case rather than the convenient one.

What a staff-level reviewer notices, in order: the invariant in a record
constructor rather than a service; PostgreSQL in the loop because H2's write
locks lie; the outbox that keeps a failed money event visible instead of
dropping it; Redis excluded from readiness with the reasoning written down; the
CI job that asks the registry whether the tag in the manifest exists, added
because it once did not; and the comment explaining why raising one
OpenTelemetry artifact broke startup and why a BOM is the fix. Those are
incident-shaped decisions. They come from operating systems, not from reading
about them.

The weaknesses are real and none is structural: a missing linter, two absent
response headers, no RLS beneath a correctly-scoped application layer, no SBOM
or signature on the published image, no router behind an nginx config already
prepared for one, and no throughput measurement behind a tuned concurrency
limit. Every item is hours of work, and none requires reversing a design
decision — which is itself the most reliable signal about the quality of those
decisions.

Overall: **strong senior, credibly staff/principal on the money-correctness
axis.** The double-entry core, the concurrency evidence, the failure-mode
reasoning and the pipeline are above the bar for a payments platform team. Close
section 6 and there is no defensible reading of this repository as anything less
than production-grade for its stated scope.

## 8. Ordered remediation

1. ESLint plus a lint job in `build.yml` (the asymmetry, and the cheapest fix).
2. CSP and HSTS in `nginx.conf` and the ingress.
3. SBOM attestation and cosign signature in `release.yml`.
4. PostgreSQL RLS as defence in depth under the tenant filters.
5. ArchUnit rules pinning the layer boundaries the layout already implies.
6. Angular Router with guards and lazy screens.
7. Raise the branch floor by covering untested decision points on money paths.
8. Split `FinanceService`; publish one throughput figure for the purchase path.

## 9. Limits of this review

Static reading only. No `mvn verify`, no `npm test`, no container build and no
cluster apply was executed in this pass, so the coverage figures are quoted from
the gate configuration and the README rather than observed, and no claim here
should be read as a verified build result.
