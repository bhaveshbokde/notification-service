-- =============================================================================
--  V3 — transactional outbox and idempotency records
-- =============================================================================

-- -----------------------------------------------------------------------------
--  outbox_events
-- -----------------------------------------------------------------------------
--  THE PROBLEM THIS SOLVES
--
--  The obvious implementation of "save it, then publish it" is:
--
--      @Transactional
--      void create(...) {
--          notificationRepository.save(notification);   // committed
--          kafkaTemplate.send(topic, event);            // may fail
--      }
--
--  Those are two separate systems and there is no transaction spanning both.
--  Three ways it breaks:
--
--    * Kafka is down when send() runs. The notification is committed and will
--      never be processed. It sits PENDING forever.
--    * send() is asynchronous. It returns before the broker acknowledges, so
--      the method can commit and return 201 for an event that is then lost.
--    * The process is killed between the commit and the send. Same outcome.
--
--  Swapping the order does not help: publish-then-save means a consumer can
--  receive an event for a notification that does not exist, and the save can
--  still fail afterwards.
--
--  THE FIX
--
--  Write the event into THIS table in the SAME transaction as the
--  notification. Both commit or neither does. A separate publisher then reads
--  unpublished rows and sends them to Kafka, marking each published only after
--  the broker acknowledges.
--
--  The cost is honest and worth stating: delivery becomes at-least-once rather
--  than exactly-once. If the publisher sends successfully and dies before
--  recording that fact, the event is sent again. That is why the consumer must
--  be idempotent — which is enforced by uq_attempts_notification_number in V2
--  and by the status check in the consumer.
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,

    -- Which entity this event concerns. Kept generic (type + id rather than a
    -- foreign key) so a second aggregate can use the same outbox later without
    -- a schema change. No FK for the same reason, and because the outbox must
    -- survive the deletion of its aggregate.
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,

    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,

    -- The Kafka message key. Setting it to the notification id puts every
    -- event for one notification on the same partition, so they are processed
    -- in order relative to each other.
    message_key     VARCHAR(128) NOT NULL,

    payload         JSONB        NOT NULL,

    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- Publication bookkeeping.
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(500) NULL,

    -- Lease fields. A publisher claims a batch by stamping these; the claim
    -- expires so a publisher that dies mid-batch does not strand its rows.
    -- See the claiming query in OutboxRepository for why this is safe.
    claimed_at      TIMESTAMPTZ  NULL,
    claimed_by      VARCHAR(64)  NULL,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ  NULL,

    CONSTRAINT ck_outbox_status CHECK (status IN (
        'PENDING',    -- written, not yet sent
        'PUBLISHING', -- claimed by a publisher, send in flight
        'PUBLISHED',  -- broker acknowledged (terminal)
        'FAILED'      -- gave up after repeated failures; needs operator attention
    )),
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_published_at CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR
        (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

-- THE index for the publisher. Partial, covering only rows that still need
-- work: once an event is PUBLISHED it is dead weight in this index, and the
-- vast majority of rows are PUBLISHED. Ordered by created_at so events are
-- published roughly in the order they were created.
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE status IN ('PENDING', 'PUBLISHING');

-- Finds expired leases during recovery.
CREATE INDEX idx_outbox_claimed
    ON outbox_events (claimed_at)
    WHERE status = 'PUBLISHING';

CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- Operator view: which events gave up entirely.
CREATE INDEX idx_outbox_failed
    ON outbox_events (created_at DESC)
    WHERE status = 'FAILED';

COMMENT ON TABLE  outbox_events            IS 'Transactional outbox. Written in the same transaction as the aggregate so the two cannot diverge.';
COMMENT ON COLUMN outbox_events.message_key IS 'Kafka partition key — the notification id, so events for one notification keep their relative order.';
COMMENT ON COLUMN outbox_events.claimed_at  IS 'Lease timestamp. An expired lease is reclaimed, so a publisher that dies mid-batch strands nothing.';
COMMENT ON INDEX  idx_outbox_unpublished    IS 'Partial on unpublished rows only. Published rows are the overwhelming majority and would bloat it.';


-- -----------------------------------------------------------------------------
--  idempotency_records
-- -----------------------------------------------------------------------------
--  Makes POST /api/v1/notifications safe to retry.
--
--  A client sends a request, the server creates the notification, and the
--  response is lost to a dropped connection. The client cannot distinguish
--  "never arrived" from "worked and I missed the answer", so it retries — and
--  gets a second notification. Retrying is correct client behaviour;
--  duplicating the work is a server defect.
--
--  WHY A DATABASE TABLE AND NOT REDIS
--
--  The claim must be atomic with respect to other instances and must survive a
--  restart. A unique index gives both for free. Redis could do it with
--  SET NX, but then the guarantee depends on a cache whose eviction policy is
--  tuned for something else entirely, and a Redis outage would silently
--  degrade correctness rather than just performance.
--
--  WHY A REQUEST HASH AS WELL AS THE KEY
--
--  The key alone cannot tell a genuine retry from a client bug. If someone
--  sends Idempotency-Key: abc with one message and then reuses abc with a
--  different message, replaying the first response would silently swallow the
--  second request. Comparing a SHA-256 of the canonical request turns that into
--  an explicit 409.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id               BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,

    -- Scoped per user: two callers must be able to choose the same key without
    -- colliding. A global key space would let one caller's choice of "1" block
    -- everyone else's.
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idempotency_key  VARCHAR(128) NOT NULL,

    -- Scoped per endpoint too: the same key on two different operations is two
    -- different operations.
    endpoint         VARCHAR(100) NOT NULL,

    -- Lowercase hex SHA-256 of the canonical request body.
    request_hash     VARCHAR(64)  NOT NULL,

    -- The notification this key produced, so a replay can return it.
    notification_id  UUID         NULL REFERENCES notifications (id) ON DELETE CASCADE,

    -- The stored response, replayed verbatim. A retry must be
    -- indistinguishable from the original call, including the status code —
    -- returning a bare 409 would be correct about "I have seen this" and
    -- useless to a client that lost the first response.
    response_status  INTEGER      NULL,
    response_body    TEXT         NULL,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- The guarantee. Two concurrent requests with the same key both try to insert;
-- exactly one succeeds and the other gets a constraint violation it can
-- interpret as "someone else is handling this".
CREATE UNIQUE INDEX uq_idempotency_user_endpoint_key
    ON idempotency_records (user_id, endpoint, idempotency_key);

-- Housekeeping: keys are only useful for as long as a client might retry.
CREATE INDEX idx_idempotency_created ON idempotency_records (created_at);

COMMENT ON TABLE  idempotency_records              IS 'Replay protection for POST /notifications. The unique index is the guarantee; the pre-read is only an optimisation.';
COMMENT ON COLUMN idempotency_records.request_hash IS 'SHA-256 of the canonical request. Detects a key reused with different content, which is a 409 rather than a replay.';
COMMENT ON INDEX  uq_idempotency_user_endpoint_key IS 'Scoped per user AND per endpoint, so callers cannot collide and one key cannot span two operations.';
