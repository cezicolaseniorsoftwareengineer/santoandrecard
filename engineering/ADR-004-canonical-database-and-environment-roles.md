# ADR-004: Canonical Database and the Role of Each Environment

- Status: Accepted
- Date: 2026-08-18
- Scope: PostgreSQL instances, Keycloak persistence and credential handling across environments
- Relates to: ADR-002 (PostgreSQL is the authoritative store), ADR-003

## Context

Three PostgreSQL instances exist and each holds data the platform treats as
authoritative: the Docker Compose volume `postgres-data`, the Kubernetes
`PersistentVolumeClaim` in `k8s/postgres.yaml`, and a managed Neon database that the
packaged application can be pointed at.

Nothing said which one *is* the platform. That is not a deployment detail. ADR-002
makes PostgreSQL the source of truth for money, and a source of truth that exists in
three divergent copies is not a source of truth: the ledger any given person sees
depends on which of them they happened to start, reconciliation compares balances
that were never meant to agree, and "the balance is wrong" becomes unanswerable
because there is no instance the answer is owed against.

The divergence is also why the Keycloak persistence work is not finished. Accounts
were moved into a database precisely so they would survive recreation; which
database that is decides whether they actually do.

## Decision

**Neon is the canonical instance.** It is the only one whose state is durable and
reachable independently of one workstation or one running cluster, which is what
"canonical" has to mean for a system meant to be opened by someone other than its
author. Both `card-service` and Keycloak point at it, sharing a server but never a
schema: Keycloak lives in the `keycloak` schema, money in the default one.

The other two instances are **ephemeral by decision, not by accident**:

| Instance | Role | State expectation |
| --- | --- | --- |
| Neon managed PostgreSQL | Canonical. Money, identity, the demonstration's history | Durable; the instance reconciliation and support answer against |
| Compose volume `postgres-data` | Local development and the fast feedback loop | Disposable. May be deleted at any time; nothing is owed by it |
| Kubernetes PVC | Proving the manifests deploy and recover | Disposable. Proves the deployment, holds no history worth keeping |

Consequences of that ranking:

- No process reconciles, merges or migrates data *between* instances. They are not
  replicas and must never be treated as such; an attempt to reconcile a local volume
  against Neon would be comparing two unrelated histories.
- A schema change is proven on the disposable instances and then applied to Neon by
  Flyway on start, as it already is. Flyway's history table on Neon is the record of
  what the canonical schema is.
- Deleting the Compose volume or the PVC is a routine act requiring no approval.
  Deleting or restoring Neon is not, and is the only one of the three that needs a
  backup and restore drill — which ADR-002's release gates require and which has not
  been run.
- The credentials in `compose.yaml` and in the README are local fixtures and stay
  that way. They are safe only because the instances they open are disposable and
  local, which is now a stated property rather than an assumption.

## Credentials

Canonical means internet-reachable, which changes what a credential is worth. For
Neon:

- The credential lives only in the environment of the process that needs it
  (`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `KC_DB_*`), supplied from a git-ignored
  `.env` by `scripts/with-env.ps1`. It is never committed, never placed in a
  manifest, and never pasted into an issue, a chat or a terminal transcript that is
  kept. A credential on disk is recoverable by rotating it; one in the history is
  not, and that asymmetry is what the rule protects.
- `sslmode=require` is mandatory, per the README: without it the driver sends the
  password in the clear over a connection that leaves the machine.
- A credential that has appeared anywhere outside that environment is compromised
  and is rotated, not assessed. Exposure is a fact about where the string has been,
  not a judgement about who probably saw it.
- Rotation may be *deferred* while the platform is a development sandbox holding
  only fictitious money and nobody else's data, provided the deferral is recorded
  with the conditions that end it. Deferring is a decision someone owns; forgetting
  is not.

One credential was compromised and has been rotated; the record, the blast radius
and the evidence that the revocation took effect are in
`engineering/credential-rotation-log.md`, and the procedure is in
`engineering/runbook-rotate-database-credential.md`.

## Alternatives rejected

- **Cluster PVC as canonical.** Requires the cluster running for the platform to
  have a history at all, and a PVC lost with the cluster is a total loss with no
  provider-side recovery.
- **Local Compose volume as canonical.** Ties the system's truth to one workstation
  and makes remote demonstration impossible.
- **Leaving it unstated.** The status quo. Cheap until the first time two instances
  disagree about a balance, at which point neither is defensible.
- **Replicating between them.** Distributed writes across environments with no
  outbox, no ownership and no reconciliation — exactly what ADR-002 refuses.
- **A separate managed database for Keycloak.** The Neon plan provides one database;
  a schema boundary is sufficient separation for a sandbox, and identity and money
  sharing a server is recorded here as a known limitation rather than a design goal.

## Open items

- Development runs directly against the canonical instance, so migrations and
  exploratory work land on the only durable copy — with no backup drill behind it.
  Acceptable while the data is fictitious; it is the first thing to change when it
  is not.
- Account persistence across container recreation is proven, but against the
  **local** Compose database, not the canonical one:
  `scripts/verify-keycloak-persistence.ps1` passed on 2026-08-19. Pointing the same
  check at Neon is one environment variable away and has not been run.
- No backup or restore drill has been run against Neon, and ADR-002 requires one
  before the release gates can pass.
