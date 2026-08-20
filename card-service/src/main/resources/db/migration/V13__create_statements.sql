-- The billing cycle: what a customer owes for a period, what made it up, and
-- what they paid against it.
--
-- Purchases already existed and were settled instantly against the prepaid card.
-- A credit purchase is not settled at all until it is billed and paid, so it
-- needs somewhere to wait, a cycle to be gathered into, and a record of the
-- claim that survives being paid.

CREATE TABLE statements (
    id              UUID           PRIMARY KEY,
    tenant_id       UUID           NOT NULL,
    customer_id     UUID           NOT NULL,
    -- Sortable as text: '2026-08'. Comparing cycles is then the same operation
    -- as ordering them, with no date arithmetic in a query.
    --
    -- VARCHAR rather than CHAR, for the reason V3 had to correct across four
    -- columns: the entities map String to VARCHAR, H2 in PostgreSQL
    -- compatibility mode accepts bpchar for it, and real PostgreSQL fails
    -- Hibernate's schema validation at startup. The fast suite would have
    -- passed either way.
    cycle           VARCHAR(7)     NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    billed_total    NUMERIC(19,2)  NOT NULL DEFAULT 0,
    paid_total      NUMERIC(19,2)  NOT NULL DEFAULT 0,
    due_date        DATE,
    closed_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL,
    -- Optimistic lock. Two payments arriving at once would otherwise both read
    -- the same balance and both be allowed, settling more than was owed.
    version         BIGINT         NOT NULL DEFAULT 0,

    -- A customer has one statement per cycle. Without this, a retried close
    -- bills the same period twice and the customer owes it twice.
    CONSTRAINT statements_one_per_cycle UNIQUE (tenant_id, customer_id, cycle),

    CONSTRAINT statements_status_known CHECK (
        status IN ('OPEN', 'CLOSED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')),
    -- The domain refuses to be paid beyond its balance. The database refuses it
    -- too, because an invariant enforced in one place is enforced until somebody
    -- writes a second path to the table.
    CONSTRAINT statements_paid_within_billed CHECK (paid_total <= billed_total),
    CONSTRAINT statements_totals_not_negative CHECK (billed_total >= 0 AND paid_total >= 0),
    -- A closed statement has a due date; an open one has not been billed yet.
    CONSTRAINT statements_closed_has_due_date CHECK (
        (status = 'OPEN' AND due_date IS NULL AND closed_at IS NULL)
        OR (status <> 'OPEN' AND due_date IS NOT NULL AND closed_at IS NOT NULL))
);

-- The sweep that marks statements overdue reads by status and due date.
CREATE INDEX statements_due ON statements (status, due_date)
    WHERE status IN ('CLOSED', 'PARTIALLY_PAID');

CREATE INDEX statements_by_customer ON statements (tenant_id, customer_id, cycle DESC);

CREATE TABLE statement_items (
    id              UUID           PRIMARY KEY,
    statement_id    UUID           NOT NULL REFERENCES statements (id),
    tenant_id       UUID           NOT NULL,
    -- What produced the item, and its identity in that source. Together they are
    -- how closure consumes each item exactly once.
    source_type     VARCHAR(24)    NOT NULL,
    source_id       UUID           NOT NULL,
    description     VARCHAR(140)   NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    occurred_at     TIMESTAMPTZ    NOT NULL,

    -- The same source can be billed once, ever. A purchase that appears on two
    -- statements is a customer charged twice for one thing, and it is the kind
    -- of defect that is only noticed by the person paying.
    CONSTRAINT statement_items_source_billed_once UNIQUE (tenant_id, source_type, source_id),

    CONSTRAINT statement_items_source_known CHECK (source_type IN ('PURCHASE', 'INSTALLMENT', 'ADJUSTMENT')),
    CONSTRAINT statement_items_amount_positive CHECK (amount > 0)
);

CREATE INDEX statement_items_by_statement ON statement_items (statement_id, occurred_at);

CREATE TABLE statement_payments (
    id              UUID           PRIMARY KEY,
    statement_id    UUID           NOT NULL REFERENCES statements (id),
    tenant_id       UUID           NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    paid_at         TIMESTAMPTZ    NOT NULL,
    -- The ledger transaction that moved the money. A payment recorded here with
    -- no entry there would be a claim reduced without money changing hands.
    ledger_transaction_id UUID     NOT NULL,

    CONSTRAINT statement_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX statement_payments_by_statement ON statement_payments (statement_id, paid_at);

-- Credit purchases wait here until a cycle gathers them. A purchase that was
-- settled against the prepaid card never becomes a statement item, which is why
-- the column records the funding source rather than assuming one.
ALTER TABLE purchases ADD COLUMN funding_source VARCHAR(16) NOT NULL DEFAULT 'CARD';
ALTER TABLE purchases ADD CONSTRAINT purchases_funding_source_known
    CHECK (funding_source IN ('CARD', 'CREDIT'));

-- Finding the unbilled credit purchases of a cycle is the read that closure
-- makes, and it is the one read that must not become slow as history grows.
CREATE INDEX purchases_unbilled_credit ON purchases (tenant_id, customer_id, created_at)
    WHERE funding_source = 'CREDIT';
