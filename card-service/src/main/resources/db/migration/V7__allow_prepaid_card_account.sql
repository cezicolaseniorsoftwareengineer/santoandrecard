-- Loading the card introduced a new entry kind and a new account. Both are
-- constrained in SQL as well as in the enum, so the database refuses a posting
-- the domain never intended to make — which is exactly what happened here: the
-- H2 schema used by the fast test profile is generated from the entities and
-- carries no such check, so only PostgreSQL rejected it.
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_kind_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_kind
    CHECK (kind IN ('OPENING_BALANCE', 'TOP_UP', 'PURCHASE', 'CARD_LOAD'));

ALTER TABLE ledger_postings DROP CONSTRAINT ledger_postings_account_code_check;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_account_code
    CHECK (account_code IN ('CUSTOMER_WALLET', 'CARD_PREPAID', 'FUNDING',
                            'MERCHANT_PAYABLE', 'INTEREST_REVENUE'));

-- A prepaid balance belongs to a customer for the same reason a wallet does:
-- an unattributed posting would sit in the account with no owner to answer for.
ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_wallet_has_customer;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_owned_accounts_have_customer
    CHECK (account_code NOT IN ('CUSTOMER_WALLET', 'CARD_PREPAID') OR customer_id IS NOT NULL);
