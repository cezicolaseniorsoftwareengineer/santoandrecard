-- Keycloak keeps its own accounts, sessions and realm configuration, and it is
-- a separate concern from the card ledger: one is identity, the other is money.
-- They share a server here for convenience, never a schema.
--
-- Runs only when the data directory is created. An environment whose volume
-- already exists needs this once by hand:
--   docker compose exec postgres psql -U card_app -d card_platform \
--     -c "CREATE DATABASE keycloak OWNER card_app"
CREATE DATABASE keycloak OWNER card_app;
