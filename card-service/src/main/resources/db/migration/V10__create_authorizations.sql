-- Authorization with hold, capture and reversal.
--
-- Until now a purchase debited the wallet in one step, which is a wallet, not a
-- card. A real authorization holds the funds, and what happens next is the
-- merchant's decision: capture what was shipped, reverse what was not, or let
-- the hold run out.
CREATE TABLE authorizations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    card_id UUID NOT NULL,
    merchant_category VARCHAR(64) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    -- Partial capture is normal: a merchant that ships half an order takes half
    -- the hold. It can never exceed what was authorised.
    captured_amount NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (captured_amount >= 0),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('APPROVED', 'CAPTURED', 'REVERSED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_authorizations_capture_within_hold CHECK (captured_amount <= amount),
    CONSTRAINT ck_authorizations_hold_outlives_creation CHECK (expires_at > created_at)
);

CREATE INDEX idx_authorizations_customer ON authorizations (tenant_id, customer_id, created_at DESC);

-- The sweep reads only holds that are still open, so it scans the backlog rather
-- than the history.
CREATE INDEX idx_authorizations_open ON authorizations (expires_at)
    WHERE status = 'APPROVED';

-- The ledger has to accept the new account and the four new entry kinds. These
-- checks exist only in PostgreSQL, so the fast test profile cannot catch a
-- missing one — the CI job that boots against a real database can.
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_kind;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_kind
    CHECK (kind IN ('OPENING_BALANCE', 'TOP_UP', 'PURCHASE', 'CARD_LOAD',
                    'AUTHORIZATION_HOLD', 'AUTHORIZATION_CAPTURE',
                    'AUTHORIZATION_REVERSAL', 'AUTHORIZATION_EXPIRY'));

ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_account_code;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_account_code
    CHECK (account_code IN ('CUSTOMER_WALLET', 'CUSTOMER_HELD', 'CARD_PREPAID', 'FUNDING',
                            'MERCHANT_PAYABLE', 'INTEREST_REVENUE'));

ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_owned_accounts_have_customer;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_owned_accounts_have_customer
    CHECK (account_code NOT IN ('CUSTOMER_WALLET', 'CUSTOMER_HELD', 'CARD_PREPAID')
           OR customer_id IS NOT NULL);
