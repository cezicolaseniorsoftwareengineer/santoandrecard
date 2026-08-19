# Runbook: Rotating the Canonical Database Credential

For the Neon credential that `card-service` and Keycloak use (ADR-004). Ten
minutes, most of it waiting. Rotation is what makes an exposed password worthless;
everything else is bookkeeping.

Do it in this order. Rotating first and fixing `.env` afterwards means a few minutes
where nothing can connect, which is fine here and would not be in production — there
the new credential is added alongside the old one, deployed, and only then is the old
one revoked. Neon's owner role does not support two passwords at once, so this
runbook takes the short outage instead.

---

## Step 1 — Rotate in the Neon console

1. Open <https://console.neon.tech> and select the project.
2. In the left sidebar, open **Branches** and select the branch holding the data
   (`main` unless you created another).
3. Open the **Roles** tab. You are looking for `neondb_owner` — the role in
   `DB_USERNAME`.
4. On that row, open the **⋯** menu and choose **Reset password**. Confirm.
5. Neon generates a new password and **shows it once**. Copy it now. There is no
   screen that displays it again; losing it means rotating again, which costs
   nothing but time.

What just happened: the old password stopped being accepted for *new* connections
immediately. That is the entire point — the copies of it sitting in chat transcripts
now open nothing.

> Connections already established may keep working until they close. Do not read a
> still-running application as proof that rotation failed.

## Step 2 — Put the new password in `.env`

Open `.env` at the repository root — the file git ignores. Replace the password in
**both** places:

```
DB_PASSWORD=<the new password>
KC_DB_PASSWORD=<the same new password>
```

`DB_URL` and `KC_DB_URL` do not change: the host, the database and the schema are
the same. Only the secret moved.

Two things that waste ten minutes if missed:

- **No quotes, no trailing space.** A password copied with a space at the end fails
  with `password authentication failed`, which reads like the wrong password rather
  than a whitespace problem.
- **Both lines.** Updating only `DB_PASSWORD` leaves Keycloak unable to reach its
  schema, and identity fails while money works — a confusing half-outage.

## Step 3 — Prove the new credential works

```bash
./scripts/with-env.sh mvn -pl card-service quarkus:dev        # macOS and Linux
```

```powershell
powershell -File ./scripts/with-env.ps1 mvn -pl card-service quarkus:dev   # Windows
```

Look for these two lines. Both must appear:

```
Flyway ... Successfully validated 11 migrations
card-service ... started in N.NNNs. Listening on: http://0.0.0.0:8080
```

`password authentication failed for user "neondb_owner"` means the value in `.env`
does not match what Neon issued — re-copy it. `Acquisition timeout` on the very
first attempt usually means the instance was suspended and is waking; try once more
before suspecting the credential.

Restart anything still running with the old password: it holds an open connection
that will fail the moment it reconnects.

## Step 4 — Record it

Rotation is only finished when the record says so. In
`engineering/credential-rotation-log.md`, change the deferral entry to state the
date rotated and what that closed. The log never holds the credential — only what
happened to it.

Then review Neon's connection logs for the exposure window. Until someone looks,
"nobody used it" is an assumption, not a finding.

## Step 5 — The places the old password still lives

Rotation revokes the password; it does not erase the copies. After rotating, these
are harmless, and it is worth knowing they exist:

- the two assistant chat transcripts,
- any terminal scrollback where it was typed,
- your clipboard history, if the operating system keeps one.

None of them require action once the password is revoked. That is precisely why
rotation is the fix and deletion is not.
