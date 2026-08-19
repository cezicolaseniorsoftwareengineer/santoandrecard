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

**Action — rotated 2026-08-18.** Rotation was first deferred by the operator, on
the grounds that the platform was in development with fictitious money and nobody
else's data. It was then carried out the same day, before the work was published to
the public GitHub repository: the documents describing this exposure were about to
become readable by anyone, and a public record of a live compromised credential is
an invitation rather than a disclosure.

Reset through the Neon console on the `neondb_owner` role. **Verified, not assumed**
— the old password was tried against the instance and refused with
`password authentication failed`, which is the evidence that the revocation took
effect rather than the console merely reporting success. The new credential connects
for both `card-service` and Keycloak, and the schema is unchanged: 10 tables, Flyway
at v11, the `keycloak` schema still present.

**The old password is now inert.** It still exists in two assistant chat
transcripts and in terminal scrollback, and those copies no longer matter — which is
the reason rotation is the fix and deleting the copies never was.

**One thing rotation exposed.** Only `KC_DB_PASSWORD` was updated in `.env` at
first; `DB_PASSWORD` still held the revoked value. Identity would have worked while
money failed — the half-outage the runbook warns about, and the reason step 3 of
that runbook tests both credentials separately rather than starting the application
and calling a clean boot proof.

**Second exposure, 2026-08-18.** The same credential was pasted into an assistant
chat a second time, while setting up `.env`. It does not widen the blast radius —
the string was already compromised and already unrotated — but it does mean the
value now sits in two transcripts rather than one, and rotation is what ends both.
The exposure is unchanged in kind: still no third party's data, still fictitious
money, still deferred by the same decision and the same conditions.

**Follow-up not yet done.** Neon's connection logs have not been reviewed for
sessions from unrecognised addresses during the exposure window. Until that review
happens, "no access occurred" is an assumption, not a finding. The window is now
closed at both ends, so the review is bounded and still worth doing.

**What changed so it does not recur.** The rule was written down rather than left as
a habit: ADR-004 states that the canonical credential lives only in the environment
of the process that needs it, and that a credential appearing anywhere else is
rotated on that fact alone. Local fixtures in `compose.yaml` remain in the clear
deliberately — ADR-004 makes those instances disposable, which is what makes a
published password harmless there.
