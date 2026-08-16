CREATE TABLE cards (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    credit_limit NUMERIC(19, 2) NOT NULL CHECK (credit_limit > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'BRL'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CANCELLED')),
    last_four_digits CHAR(4) NOT NULL CHECK (last_four_digits ~ '^[0-9]{4}$'),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_cards_customer_id ON cards (customer_id);
