ALTER TABLE cards ADD COLUMN tenant_id UUID;
UPDATE cards SET tenant_id = '00000000-0000-0000-0000-000000000000';
ALTER TABLE cards ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE cards DROP CONSTRAINT cards_idempotency_key_key;
ALTER TABLE cards ADD CONSTRAINT uq_cards_tenant_idempotency UNIQUE (tenant_id, idempotency_key);
CREATE INDEX idx_cards_tenant_customer ON cards (tenant_id, customer_id);

CREATE TABLE wallets (
    wallet_key VARCHAR(73) PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, customer_id)
);

CREATE TABLE interest_policies (
    tenant_id UUID PRIMARY KEY,
    monthly_rate NUMERIC(9, 6) NOT NULL CHECK (monthly_rate >= 0 AND monthly_rate <= 1),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE purchases (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    merchant_category VARCHAR(64) NOT NULL,
    principal NUMERIC(19, 2) NOT NULL CHECK (principal > 0),
    interest NUMERIC(19, 2) NOT NULL CHECK (interest >= 0),
    total NUMERIC(19, 2) NOT NULL CHECK (total = principal + interest),
    installments INTEGER NOT NULL CHECK (installments BETWEEN 1 AND 24),
    installment_amount NUMERIC(19, 2) NOT NULL CHECK (installment_amount > 0),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_purchases_tenant_customer ON purchases (tenant_id, customer_id, created_at);
