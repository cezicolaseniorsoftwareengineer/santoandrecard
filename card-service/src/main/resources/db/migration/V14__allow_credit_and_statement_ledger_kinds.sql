-- The credit product and the billing cycle arrived in V13 with new transaction
-- kinds and a new account, and the check constraints that enumerate them were
-- never widened to match. On PostgreSQL that is not a degraded feature: every
-- credit purchase and every statement payment is refused by the database, so
-- the whole credit path is dead in any environment that ran the migrations.
--
-- It survived to here because the fast suite builds its schema from the
-- Hibernate mapping instead of from these files, and a mapping carries no CHECK.
-- The constraint and the enum it mirrors have to move together or the mirror is
-- decoration; the concurrency test on real PostgreSQL is what finally read it.

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_kind;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_kind
    CHECK (kind IN ('OPENING_BALANCE', 'TOP_UP', 'PURCHASE', 'CARD_LOAD',
                    'AUTHORIZATION_HOLD', 'AUTHORIZATION_CAPTURE',
                    'AUTHORIZATION_REVERSAL', 'AUTHORIZATION_EXPIRY',
                    'CREDIT_PURCHASE', 'STATEMENT_PAYMENT'));

-- CUSTOMER_RECEIVABLE is what the customer owes the issuer: a debit-normal
-- account, and the only per-customer account added since V10.
ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_account_code;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_account_code
    CHECK (account_code IN ('CUSTOMER_WALLET', 'CUSTOMER_HELD', 'CARD_PREPAID', 'FUNDING',
                            'MERCHANT_PAYABLE', 'INTEREST_REVENUE', 'CUSTOMER_RECEIVABLE'));

-- Per-customer accounts must name the customer they belong to. A receivable
-- with no owner is a debt nobody can be billed for.
ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_owned_accounts_have_customer;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_owned_accounts_have_customer
    CHECK (account_code NOT IN ('CUSTOMER_WALLET', 'CUSTOMER_HELD', 'CARD_PREPAID',
                                'CUSTOMER_RECEIVABLE')
           OR customer_id IS NOT NULL);
