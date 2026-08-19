# Banco Santo André Web

The Angular interface for the card platform. It signs in through Keycloak with
the authorization code flow and PKCE, and every figure it shows came from an API
response — nothing is computed here, so the screen cannot disagree with the
ledger.

## Structure

| Path | What lives there |
| --- | --- |
| `src/app/app.component.ts` | The shell. It owns which screen is on, the boot sequence, and the one place an API call becomes a busy cursor or a message. |
| `src/app/ui/` | The screens and the pieces they are made of. Each reads from the store and reports what the user asked for; none decides what a failure means. |
| `src/app/bank-store.service.ts` | Application state, backed by the API. |
| `src/app/auth.service.ts` | The PKCE flow. The access token is held in memory and the refresh token in `sessionStorage`, so closing the tab ends the session. |
| `src/styles.css` | Design tokens and the interface stylesheet, global on purpose — it is the vocabulary every component draws from. |
| `e2e/` | The browser journey, against the real identity provider and the real API. |

## Running it

The interface needs the API and Keycloak. From the repository root:

```bash
docker compose up -d
cd card-service && mvn quarkus:dev
```

Then:

```bash
npm ci
npm start          # http://localhost:4420
```

## Quality gates

```bash
npm test           # component and store tests
npm run build      # production bundle, with budgets enforced
npm audit --audit-level=high
```

The browser journey is separate, because it needs the whole stack running. It
starts the production bundle itself, registers a fresh cardholder on Keycloak,
issues a card, funds it, buys, and checks that the card number stays masked
until the right PIN is given:

```bash
npm run e2e
npm run e2e:report    # only worth opening when something failed
```
