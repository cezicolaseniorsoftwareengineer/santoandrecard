-- Double-entry ledger.
--
-- Every movement of money is a transaction made of at least two postings whose
-- debits and credits are equal. Postings are append-only: a mistake is corrected
-- by a compensating transaction, never by editing history.
--
-- The chart of accounts is a fixed set of codes rather than a table, because the
-- accounts this product uses are decided by its own logic, not configured by an
-- operator. CUSTOMER_WALLET is a sub-ledger keyed by customer_id.

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'TOP_UP', 'PURCHASE')),
    description VARCHAR(140) NOT NULL,
    -- Identifies the domain object that caused the movement, so a purchase can be
    -- traced to its postings and back.
    reference_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ledger_transactions_tenant ON ledger_transactions (tenant_id, occurred_at DESC);
CREATE INDEX idx_ledger_transactions_reference ON ledger_transactions (reference_id);

CREATE TABLE ledger_postings (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions (id),
    tenant_id UUID NOT NULL,
    account_code VARCHAR(32) NOT NULL
        CHECK (account_code IN ('CUSTOMER_WALLET', 'FUNDING', 'MERCHANT_PAYABLE', 'INTEREST_REVENUE')),
    -- Present only for the accounts that are kept per customer.
    customer_id UUID,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ledger_postings_wallet_has_customer
        CHECK (account_code <> 'CUSTOMER_WALLET' OR customer_id IS NOT NULL)
);

CREATE INDEX idx_ledger_postings_wallet
    ON ledger_postings (tenant_id, account_code, customer_id, occurred_at DESC);
CREATE INDEX idx_ledger_postings_transaction ON ledger_postings (transaction_id);

-- Opening balances. Wallets existed before the ledger did, so each one gets a
-- single opening transaction carrying its current balance. Inventing a history of
-- movements that never happened would be worse than declaring the starting point.
INSERT INTO ledger_transactions (id, tenant_id, kind, description, reference_id, occurred_at)
SELECT w.wallet_key_uuid, w.tenant_id, 'OPENING_BALANCE', 'Saldo inicial migrado', NULL, CURRENT_TIMESTAMP
FROM (
    SELECT tenant_id, customer_id, balance,
           CAST(md5(wallet_key) AS UUID) AS wallet_key_uuid
    FROM wallets
    WHERE balance > 0
) w;

INSERT INTO ledger_postings (id, transaction_id, tenant_id, account_code, customer_id, direction, amount, occurred_at)
SELECT CAST(md5(w.wallet_key || ':debit') AS UUID), CAST(md5(w.wallet_key) AS UUID), w.tenant_id,
       'FUNDING', NULL, 'DEBIT', w.balance, CURRENT_TIMESTAMP
FROM wallets w WHERE w.balance > 0;

INSERT INTO ledger_postings (id, transaction_id, tenant_id, account_code, customer_id, direction, amount, occurred_at)
SELECT CAST(md5(w.wallet_key || ':credit') AS UUID), CAST(md5(w.wallet_key) AS UUID), w.tenant_id,
       'CUSTOMER_WALLET', w.customer_id, 'CREDIT', w.balance, CURRENT_TIMESTAMP
FROM wallets w WHERE w.balance > 0;
