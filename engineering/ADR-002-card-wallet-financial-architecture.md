# ADR-002: Card, Wallet, Billing and Tenant Architecture

- Status: Proposed; implementation may proceed behind non-production simulation gates
- Date: 2026-08-16
- Scope: Banco Santo Andre educational card and wallet platform
- Supersedes: none
- Amended by: ADR-003 (simulated card number and PIN verification)

## Context and boundaries

The product is an original educational simulation for the fictitious Banco Santo André. It may reproduce common card-domain behavior, but it must not copy any real institution's branding, source code, private APIs, product terms or screens, and must not imply affiliation with one. No real deposit, card-network authorization, acquiring, settlement or regulated banking operation is in scope.

The current repository contains a Quarkus `card-service` with a `Card` aggregate and PostgreSQL persistence. PostgreSQL is the authoritative store. Redis and Kafka are infrastructure seams, not financial sources of truth.

## Decision

Build the MVP as a modular financial platform with explicit bounded contexts. Keep deployment boundaries independently extractable, but avoid distributed writes until each boundary has an outbox, inbox, reconciliation and operational owner.

| Context | Responsibilities | Authoritative state |
| --- | --- | --- |
| Identity and Access | registration, authentication, session lifecycle, MFA-ready factors, roles and tenant membership | identity store / OIDC provider |
| Customer and Tenant | tenant, customer profile, status and ownership relationships | PostgreSQL |
| Card | card lifecycle, token-safe display data, limits and blocks | PostgreSQL |
| Authorization | purchase authorization, limit reservation, reversal and expiry | PostgreSQL reservation journal |
| Ledger and Wallet | immutable double-entry postings and rebuildable balances | PostgreSQL journal |
| Billing | statement cycles, statement items, minimum payment, payment allocation and delinquency | PostgreSQL |
| Pricing | effective-dated interest, fee and installment policies | PostgreSQL |
| Merchant Simulation | controlled merchant catalog and simulated purchase initiation | PostgreSQL |
| Administration | policy commands, support actions and read models; never direct journal mutation | source contexts plus audit journal |

Kafka carries versioned domain events through transactional outbox/inbox. Redis may cache catalogs, sessions, rate limits and rebuildable read models; it must never decide the authoritative balance, available limit, invoice amount or payment outcome.

## Tenant and authorization model

`tenant_id` identifies an isolated institutional realm. Banco Santo Andre is the initial tenant; the model supports future educational realms without sharing financial data. `customer_id` identifies a customer inside a tenant and is not a tenant substitute. Every owned aggregate, unique key, query, event, audit record and cache key includes `tenant_id`.

Tenant context is derived from a verified issuer/audience-bound token and server-side membership, never accepted from an untrusted request body or query parameter. PostgreSQL row-level security is defense in depth, while application authorization remains mandatory. Service credentials are tenant-scoped where feasible.

Initial roles:

- `CUSTOMER`: access only owned wallet, cards, purchases, statements and simulations.
- `SUPPORT`: read masked customer state and perform explicitly approved reversible support actions.
- `FINANCE_OPERATOR`: manage prospective pricing policies and reconcile statements/payments.
- `RISK_OPERATOR`: block cards and manage authorization controls without changing ledger history.
- `AUDITOR`: read immutable financial and administrative evidence.
- `PLATFORM_ADMIN`: operate infrastructure; no implicit permission to post money or alter pricing.

Sensitive admin commands require MFA, reason code, correlation ID, immutable audit evidence and segregation of duties. Authorization is checked by role, tenant, object ownership and command-specific policy. A role alone is insufficient.

## Domain model

Core identities are UUIDs generated server-side. Money is `(amount: NUMERIC(19,2), currency: BRL)` with explicit rounding contracts; floating-point types are prohibited.

- `Tenant(tenantId, legalName, status)`
- `Customer(customerId, tenantId, identitySubject, status)`
- `WalletAccount(accountId, tenantId, customerId, status)`
- `Card(cardId, tenantId, customerId, walletAccountId, creditLimit, status, displayToken)`
- `Merchant(merchantId, tenantId, category, displayName, status)`
- `Purchase(purchaseId, tenantId, cardId, merchantId, amount, mode, state, idempotencyKey)`
- `AuthorizationHold(holdId, purchaseId, amount, expiresAt, state)`
- `InstallmentPlan(planId, purchaseId, installmentCount, principal, customerInterest, schedule, state)`
- `Statement(statementId, accountId, cycle, dueDate, state, totals)`
- `StatementItem(itemId, statementId, sourceType, sourceId, amount)`
- `Payment(paymentId, statementId, amount, state, idempotencyKey)`
- `PricingPolicy(policyId, tenantId, product, type, rate, calculationMethod, validFrom, validTo, version)`
- `JournalEntry(entryId, tenantId, effectiveAt, correlationId, idempotencyKey, postings)`
- `Posting(postingId, entryId, ledgerAccountId, direction, amount, currency)`

Purchase states are `REQUESTED -> AUTHORIZED -> CAPTURED -> POSTED`, with terminal or compensating paths `DECLINED`, `EXPIRED`, `REVERSED` and `REFUNDED`. Statements are `OPEN -> CLOSED -> PARTIALLY_PAID|PAID|OVERDUE -> SETTLED`; transitions are explicit and append auditable events.

## Wallet funding and credit semantics

The customer may add arbitrary **simulated** funds through a test-money faucet. The UI and API must label them as fictitious and apply configured per-command and daily limits. Funding is not a direct balance update: it posts a balanced journal entry between `SIMULATION_FUNDING_SOURCE` and the customer's wallet liability account.

Wallet balance and revolving card credit are distinct concepts. A wallet top-up increases simulated wallet funds; it does not silently increase the credit limit. Paying a statement transfers simulated wallet value to card receivables and restores available limit only under an explicit payment-allocation rule.

Purchases may be debit-like wallet purchases, single-payment credit purchases or installment credit purchases. The selected funding source is immutable after authorization; changes require reversal and a new purchase.

## Installments, statements and interest

An installment schedule is materialized at purchase acceptance with principal, interest, taxes/fees when applicable, due dates and rounding residue allocation. The sum of scheduled principal equals the purchase principal; the sum of installments equals principal plus disclosed charges. A final-installment adjustment absorbs cent-level rounding residue deterministically.

Pricing policies are append-only/effective-dated. Admins may create a future policy version, but cannot edit a policy already referenced by a purchase, statement or simulation quote. Every quote and charge records policy ID, version, calculation basis, periodic rate, effective annual rate when displayed, rounding rule and timestamp.

The first MVP supports deterministic fixed-payment simulations and configurable purchase-installment interest. Revolving interest, late interest, penalties, taxes, payment hierarchy, grace periods and refinancing remain blocked until product rules and independent calculation fixtures are approved. Interest accrual posts to the ledger; it never exists only as a statement total.

Statement closure consumes captured/postable items exactly once using stable item identities. Payments are allocated deterministically and idempotently. Reopening or deleting a closed statement is forbidden; corrections use adjustments or reversal entries.

## Accounting model and invariants

The journal is append-only and double-entry. Balances, admin totals, customer dashboards and statements are projections that can be rebuilt and reconciled.

Mandatory invariants:

1. For every journal entry and currency, total debits equal total credits.
2. Every financial command has tenant, identity, causality, correlation and an idempotency key enforced by a database uniqueness constraint.
3. The same idempotency key with a different canonical request hash fails closed.
4. A purchase cannot be captured above its active authorization, captured twice or reversed beyond captured value.
5. `availableCredit = creditLimit - activeHolds - postedUnpaidPrincipal + eligiblePostedPayments`, under a versioned policy, never from Redis.
6. Wallet and available-credit projections never silently diverge from the journal/reservation sources; reconciliation detects and blocks ambiguous continuation.
7. A posting, statement item, installment and payment belongs to exactly one tenant and cannot reference a cross-tenant aggregate.
8. Closed financial history is corrected with compensating entries, never update/delete.
9. No admin command can retroactively change applied rates, amounts or journal entries.
10. No PAN, CVV, password, token, full document or sensitive financial payload appears in logs, Kafka events or audit details.

Concurrency uses account/card-scoped serialization through optimistic versions or database locks plus retry with bounded backoff. Broker delivery is at-least-once; business effects are exactly-once only through transactional uniqueness and state-machine guards.

## API and event contracts

Commands require `Idempotency-Key`; responses expose correlation IDs and stable problem details without sensitive data. APIs are versioned. List endpoints are tenant-filtered, bounded and cursor-paginated.

Events include `eventId`, `eventType`, `schemaVersion`, `tenantId`, `aggregateId`, `aggregateVersion`, `occurredAt`, `correlationId`, `causationId` and a minimized payload. The producing transaction writes state and outbox atomically. Consumers persist inbox deduplication and projection change atomically. Schema compatibility and replay tests are release gates.

## Threat model and controls

| Threat | Required control |
| --- | --- |
| IDOR or forged tenant context | token-derived tenant, membership check, object authorization, RLS, negative cross-tenant tests |
| Admin account takeover | phishing-resistant MFA readiness, short sessions, step-up auth, least privilege, immutable audit and alerts |
| Balance/limit race and duplicate requests | database constraints, state-machine guards, scoped locking, idempotency and concurrent tests |
| Rate manipulation or retroactive pricing | effective-dated immutable policies, dual approval for sensitive changes, signed audit evidence |
| Kafka duplicate, reorder or poison event | outbox/inbox, aggregate version checks, retry/DLQ policy and deterministic replay |
| Redis loss or stale cache | fail to PostgreSQL authority; rebuild cache and never authorize from cached balance alone |
| Ledger tampering | append-only permissions, hash-linked evidence where useful, reconciliations, restricted break-glass access |
| Credential/PII leakage | OIDC password handling, encryption in transit/at rest, masking, log allowlist, secret scanning |
| Simulation mistaken for real funds | persistent sandbox banner, fictitious-money vocabulary, isolated environment and no external payment rails |
| Abuse of funding faucet or merchant simulation | quotas, rate limiting, fraud signals, deterministic limits and operator alerts |
| XSS/CSRF/session theft in Angular | CSP, output encoding, secure HttpOnly SameSite cookies/BFF preference, CSRF tokens and dependency scanning |

The platform should avoid handling raw card credentials. If later connected to a card processor, use provider tokenization and hosted components to minimize PCI scope; never store CVV.

## Delivery sequence

1. Establish original brand, Angular shell, OIDC-compatible identity boundary, tenant membership and customer/admin route guards.
2. Migrate the existing card schema to include `tenant_id`, ownership constraints and versioned limit history; prove cross-tenant isolation.
3. Implement append-only double-entry ledger and simulated funding with model-based, property and concurrency tests.
4. Implement merchant catalog, purchase authorization/hold/reversal and transactional outbox/inbox.
5. Implement deterministic installment quotation and schedules with golden calculation fixtures.
6. Implement statement cycles, closure, payments, allocation and adjustments with replay/reconciliation tests.
7. Add effective-dated admin pricing, four-eyes approval for sensitive changes and immutable audit views.
8. Add operational dashboards, SLOs, backup/restore drills, dependency/container scanning and failure simulations.

Each increment requires unit, API contract, PostgreSQL integration, tenant isolation, authorization, concurrency, failure/recovery and end-to-end tests proportional to its risk. H2 compatibility tests do not replace PostgreSQL evidence.

## Alternatives rejected

- Direct mutable balance columns as financial truth: cannot prove history or reliably reconcile.
- Redis-backed authorization truth: cache loss/staleness can create unauthorized spending.
- One unrestricted `ADMIN` role: violates least privilege and segregation of duties.
- Editable global interest percentage: makes historical bills non-reproducible and enables retroactive changes.
- Immediate microservice extraction for every context: increases partial-failure surface before contracts and recovery are proven.
- Treating every customer as a tenant: conflates ownership with institutional isolation and complicates future policy boundaries.

## Vetoes and release conditions

Production or real-money use is vetoed until all conditions below have observable evidence:

- No password-only production authentication; OIDC, secure credential lifecycle, MFA for admins and object-level authorization are proven.
- Tenant isolation is enforced in schema, application, queries, events, caches and tests, including adversarial cross-tenant access.
- Ledger balance, idempotency, no-double-capture, no-over-reversal and deterministic interest properties pass against PostgreSQL under concurrency.
- Funding is either unmistakably simulated and isolated, or integrated with an authorized payment rail and reconciled; users cannot create real value arbitrarily.
- Interest, installments, statements, minimum payment, delinquency, taxes and disclosures have approved product/legal rules. This ADR is not legal or regulatory approval.
- Outbox/inbox, replay, reconciliation, backup/restore, RTO/RPO, observability, alerting and incident runbooks are exercised.
- Secrets, PII, PCI scope, audit retention, LGPD duties and supply-chain controls are assessed against the actual deployment.
- Admin aggregate totals are reconciled projections, never manual balances or unscoped queries.
- Load, fault-injection and disaster-recovery evidence meet defined SLOs; a green compile alone is insufficient.

Until these gates pass, the acceptable release target is a local or isolated educational sandbox using fictitious funds and explicit non-bank disclaimers.
