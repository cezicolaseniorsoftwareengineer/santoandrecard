-- Idempotency for the operations that move money.
--
-- Card issuance already had this guarantee, enforced by a unique index rather
-- than by checking and then inserting. Top-up, purchase and card load did not,
-- and those are exactly the calls a client retries after a timeout it cannot
-- interpret. A retry must return the original outcome, not perform a second one.
CREATE TABLE idempotency_records (
    tenant_id UUID NOT NULL,
    -- The operation is part of the key: the same client key used on a top-up and
    -- on a purchase describes two different intents, and collapsing them would
    -- answer one with the other's result.
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    -- Digest of the request. A key replayed with a different body is a client
    -- defect, and it is refused rather than silently answered with the first
    -- request's outcome.
    request_digest VARCHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, operation, idempotency_key)
);

-- Records are pruned by age, so the sweep reads a range rather than the table.
CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);
