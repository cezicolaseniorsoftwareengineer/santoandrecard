-- The transactional outbox.
--
-- An event must be published exactly when the state change it describes is
-- committed. Publishing inside the transaction would emit events for work that
-- later rolls back; publishing after it would lose events when the process dies
-- in between. Neither is acceptable for money. So the intent to publish is
-- written here, in the same transaction as the change itself, and a separate
-- reader turns intent into delivery.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    -- Partition key. Events for one customer must arrive in the order they were
    -- recorded, and Kafka only orders within a partition.
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- Null while the event is still owed to the broker.
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);

-- The reader only ever wants the unpublished tail, oldest first. A partial index
-- keeps that scan proportional to the backlog rather than to the history.
CREATE INDEX idx_outbox_unpublished ON outbox_events (occurred_at)
    WHERE published_at IS NULL;
