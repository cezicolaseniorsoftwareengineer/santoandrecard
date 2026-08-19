# ADR-003: Simulated Card Number and PIN Verification

- Status: Accepted for the isolated educational sandbox; production use stays vetoed by ADR-002
- Date: 2026-08-18
- Scope: `card-service` card number issuance, PIN storage and number reveal
- Relates to: ADR-002 (Card, Wallet, Billing and Tenant Architecture)

## Context

ADR-002 defines the `Card` aggregate with "token-safe display data" and states that
the platform should avoid handling raw card credentials. It does not decide how a
cardholder proves possession before seeing a full card number, because at the time
no such flow existed.

The interface then required one: a simulated card that never shows its number is
not a usable demonstration of a card product. Implementing the reveal introduced a
credential — a PIN — and a stored full number. Both are decisions ADR-002 does not
cover, and a credential mechanism that exists only in code is exactly the kind of
choice that must be recorded rather than inferred from a diff.

This ADR records the decision already made in code (`CardNumber`, `CardPin`,
`CardService.setPin`/`revealNumber`, migration `V6`) so the judgement behind it is
auditable, and states the limits under which it is acceptable.

## Decision

### The stored number is not a PAN

`CardNumber` is a 16-digit number built on the fictitious BIN `999900`, which is
assigned to no card scheme. It is issued by nothing, routed by nothing and
authorises nothing: purchases in this platform settle against the customer's own
simulated balance and are never presented to an acquirer. It satisfies the Luhn
check so that any interface or validator treating it as a card number behaves as it
would in production.

Consequently the number is a property of a demonstration card, not cardholder data,
and storing it in `card-service` does not place this service in PCI scope. This
holds **only** while the number reaches no network. If the platform is ever
connected to a real processor this decision is void: the PAN moves to provider-side
tokenisation and this column holds a token, per ADR-002's threat model.

CVV is not modelled and must not be. There is no acceptable reason for this service
to hold one.

### The PIN is derived, never stored

`CardPin` keeps only `(salt, hash)` from PBKDF2-HMAC-SHA256, 210,000 iterations,
256-bit output, 16-byte random salt from `SecureRandom`. Verification compares in
constant time (`MessageDigest.isEqual`); an early-exit comparison leaks how much of
the derived key was guessed. The `PBEKeySpec` copy of the plaintext is cleared in a
`finally` block rather than left for the collector.

The plaintext PIN is accepted once, in the body of a POST, and is never logged,
cached, emitted on an event or returned. This satisfies ADR-002 invariant 10.

### Derivation is not the control against a live attacker

A four-digit PIN is ten thousand possibilities. No iteration count makes that
unguessable online. The derivation makes an exposed database useless; it does not
defend a live card. Two separate controls do:

1. **A durable attempt budget on the card.** `cards.pin_attempts`, five attempts,
   persisted. It is persisted rather than held in memory precisely so that
   restarting the service does not hand an attacker a fresh budget. The counter
   belongs to the card because the card is what gets locked. It is committed in its
   own transaction *before* the failure is raised — counting the attempt inside the
   transaction that then throws would roll the increment back and make the budget
   unlimited. A correct PIN resets it to zero, so isolated mistakes never accumulate
   into a lock for a cardholder who does know the PIN.

2. **A short shared window.** Three attempts per minute per card, in Redis
   (`RedisRateLimiter`, fixed window, expiry anchored on the first increment only —
   refreshing it per request would let a steady stream hold the key alive and never
   reset the count). Five attempts are no defence if all five can be spent in a
   burst across replicas before any row is written; this window is what makes the
   durable budget cost an attacker time.

The rate limiter **degrades open**: if Redis is unreachable the request is allowed,
and a deployment may run with Redis off entirely (`card.redis.enabled=false`,
`NoRedisFallbacks`). This is deliberate and it is a real, accepted weakening. The
throttle guards nothing on its own — the authoritative budget behind it is still
enforced and still locks the card at five — so taking the service down because a
cache is missing would trade a small risk for a certain outage. The residual
exposure is that an attacker able to burst concurrently spends the whole budget
faster; they still cannot exceed it.

Unlocking is by setting a new PIN, which resets the counter. There is deliberately
no support-side unlock: it would create a support action that defeats the control,
and per ADR-002 such a command would require MFA, reason code and segregation of
duties before it could exist.

### Authorisation is checked in the application layer

Both `setPin` and `revealNumber` resolve the card through
`ownedCard(tenantId, customerId, cardId)` in `CardService`, not in the resource, so
no future entry point can reach the flow without the check. A card belonging to
another customer is reported as absent, never as forbidden — a 403 would confirm
that the identifier exists. Tenant and customer come from the verified token, never
from the request, per ADR-002.

### Failures are coarse on purpose

`CardPinException` carries a reason and the remaining attempts, nothing else. The
HTTP mapping is `403` incorrect (the caller is authenticated and owns the card; it
failed a second factor, so not `401`), `423` locked (retrying will not help, and it
is a distinct situation for the interface), `429` throttled, `409` no PIN set. The
body reports `attemptsRemaining` so a cardholder is not locked out by surprise, and
says nothing about the PIN itself.

## Alternatives rejected

- **Show the number without any check.** The reveal would be equivalent to reading
  the card list, and the interface would teach a habit that is wrong for the real
  product it simulates.
- **Store the PIN encrypted rather than derived.** Reversible storage means a key
  compromise yields every PIN. A PIN never needs to be read back.
- **Counter in memory or in Redis only.** A restart or an eviction restores the
  attacker's budget. The control must be as durable as the card.
- **Rate limiter as the only defence.** It degrades open and it is per-window; alone
  it bounds nothing over a long horizon.
- **Fail closed when Redis is unreachable.** Makes an optional cache a hard
  dependency of card access, for a control that is secondary to the durable budget.
- **A longer PIN, or reusing the account password.** A longer PIN departs from the
  artefact being simulated; reusing the account password makes one credential guard
  two things at two different exposure levels.
- **Six attempts, ten attempts, or no lock.** Five matches the common issuer
  envelope and keeps the keyspace explored per lock at 0.05%.

## Consequences

- A cardholder who forgets the PIN always recovers by setting a new one; there is no
  unrecoverable state and no support escalation path to build.
- `pin_attempts` is written outside the reveal's transaction, so a failed attempt
  costs up to two commits. Accepted: correctness of the counter outranks the cost.
- Migration `V6` backfills numbers for pre-existing cards and computes the Luhn
  check digit in SQL, because a backfill writing numbers the domain would reject
  would fail on first read instead of at migration time. `card_number` becomes
  `NOT NULL` with a digit-shape check constraint afterwards.
- Card numbers are unique (`uq_cards_number`, migration `V11`), globally rather
  than per tenant: the number identifies a card as an artefact, and two tenants
  issuing the same one would be indistinguishable to anything reading it as a card
  number. Generation is random, so a collision is a fact about the draw and not
  about the request; the insert redraws, bounded at five attempts, and counts
  `card.number.collisions`. The constraint check is by name — an unnamed integrity
  violation read as an idempotency replay would send a caller looking for a card
  that was never written.
- PBKDF2 at 210k iterations was measured, not estimated (`PinVerificationCostTest`,
  excluded from the normal build, run with
  `mvn test -Dgroups=benchmark -Dtest.excludedGroups=none`). On the reference
  developer machine (12 cores, JDK 17.0.16): **p50 183 ms, p95 273 ms serial**; with
  all cores deriving at once, **p50 539 ms** and a ceiling of **~22 verifications per
  second for the whole process**.

  That is an order of magnitude above what this ADR originally assumed, and it
  changes the risk rather than merely the latency. A reveal is CPU-bound for a fifth
  of a second, so roughly a dozen concurrent reveals saturate every core and starve
  every unrelated request in the same process — a cost paid by the service, not by
  the attacker. The per-card throttle does not bound this, because it is per card:
  an authenticated caller with many cards, or many callers, walks around it, and it
  degrades open besides.

  The iteration count is not being lowered: it is the OWASP figure and it is what
  makes an exposed database useless. The exposure is accepted for the sandbox and
  the mitigations are recorded as required before any wider deployment — a global
  concurrency bound on the derivation, so that saturation degrades the reveal path
  alone; a per-identity throttle alongside the per-card one; and derivation on a
  bounded worker pool rather than the request thread.

## Open items

- The CPU floor of the reveal path is now measured (see Consequences), but the
  endpoint itself is not: no HTTP, pool, PostgreSQL or Redis numbers exist, and
  ADR-002's release gates require them against the deployed stack.
- The three mitigations for the derivation cost above are recorded, not implemented.
- The throttle's Redis behaviour is covered by unit tests against a stubbed
  datasource (`RedisRateLimiterTest`); it has not been exercised against a real
  Redis server or across replicas.
- Keycloak-backed identity is now proven end to end against the local stack: a
  real token is accepted, an invalid one refused with 401, and role separation
  holds. It has not been proven against the canonical database, which is a
  different deployment of the same configuration.
