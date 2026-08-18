# Credential Rotation Log

A credential is compromised by where the string has been, not by whether anyone is
believed to have read it. Exposure is therefore recorded and rotated, never assessed
away. This file records the rotation; it never records the credential.

Policy for the canonical database is in ADR-004.

---

## 2026-08-18 — Neon PostgreSQL application credential

**What was exposed.** The connection credential for the canonical Neon database
(`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, also used by Keycloak as `KC_DB_*` against the
`keycloak` schema) was pasted into an assistant chat session while configuring the
managed datasource.

**Blast radius.** Full read and write access to the canonical instance: the ledger,
wallets, cards, derived PINs, the outbox and Keycloak's account and session tables.
Write access to a double-entry journal means history could be altered rather than
merely read, and ADR-002 invariant 8 — closed history is corrected only by
compensating entries — is enforced by the application, not by the database user.

**Not in the repository.** Verified with `git grep` over the working tree for the
host, the user and `npg_`: the README documents the shape with `HOST`/`DATABASE`/
`USER`/`PASSWORD` placeholders only, `.env` and `secrets/` are ignored, and no
manifest carries the value. History was never rewritten because the value was never
committed. The exposure is the chat transcript alone.

**Action — deferred, by decision.** Rotation was raised on 2026-08-18 and the
operator deferred it: the platform is in development, the database holds only
fictitious money and no third party's data, and the credential is still needed for
active work against the canonical instance. That is a legitimate call at this stage
and it is recorded as a decision rather than left as an open task.

The credential therefore **remains live and remains compromised**. It is held in a
git-ignored `.env` at the repository root (`.env.example` documents the shape) and
loaded per-process by `scripts/with-env.ps1`, which prints variable names and never
values. On disk and ignored is the recoverable form of this exposure; committed is
the unrecoverable one, and the distinction is the whole reason for the arrangement.

**Conditions that end the deferral.** Any one of these makes rotation immediate
rather than scheduled:

- the database begins holding anything belonging to a real person;
- the instance is shown to anyone outside the operator, including in a demonstration;
- an unrecognised connection appears in Neon's logs;
- the platform is presented as anything other than a development sandbox.

**Second exposure, 2026-08-18.** The same credential was pasted into an assistant
chat a second time, while setting up `.env`. It does not widen the blast radius —
the string was already compromised and already unrotated — but it does mean the
value now sits in two transcripts rather than one, and rotation is what ends both.
The exposure is unchanged in kind: still no third party's data, still fictitious
money, still deferred by the same decision and the same conditions.

**Follow-up not yet done.** Neon's connection logs have not been reviewed for
sessions from unrecognised addresses during the exposure window. Until that review
happens, "no access occurred" is an assumption, not a finding — and while rotation
is deferred, that window has no end.

**What changed so it does not recur.** The rule was written down rather than left as
a habit: ADR-004 states that the canonical credential lives only in the environment
of the process that needs it, and that a credential appearing anywhere else is
rotated on that fact alone. Local fixtures in `compose.yaml` remain in the clear
deliberately — ADR-004 makes those instances disposable, which is what makes a
published password harmless there.
