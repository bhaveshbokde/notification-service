-- =============================================================================
--  V4 — notification priority, and a dead-letter inspection table
-- =============================================================================
--  This migration exists partly to demonstrate how a schema change is made
--  after the initial release, so it is written the way a real forward
--  migration has to be written.
--
--  THE RULES A POST-RELEASE MIGRATION MUST FOLLOW
--
--  1. Additive only. A column is added with a DEFAULT and NOT NULL, so rows
--     written by the previous application version remain valid and the old
--     code — which does not know the column exists — keeps working.
--
--  2. No rewrite of existing data beyond what the default supplies. Since
--     PostgreSQL 11, ADD COLUMN ... DEFAULT does not rewrite the table, so
--     this does not take an exclusive lock for the length of a full scan.
--
--  3. Never edit V1–V3. Flyway checksums every applied migration, including
--     its comments; changing one makes the application refuse to start with a
--     checksum mismatch. Changes go in a new file, always.
--
--  4. Indexes that would lock a busy table should be created CONCURRENTLY.
--     That is deliberately NOT done here — CREATE INDEX CONCURRENTLY cannot
--     run inside a transaction, and Flyway wraps each migration in one. The
--     correct approach for a genuinely large table is a separate migration
--     annotated to run outside a transaction. At this table's size the plain
--     form is honest and simpler; the trade-off is recorded rather than hidden.
-- =============================================================================

-- -----------------------------------------------------------------------------
--  Priority
-- -----------------------------------------------------------------------------
--  A password-reset email and a marketing digest should not share a queue
--  position. Priority lets the publisher drain urgent work first.
-- -----------------------------------------------------------------------------
ALTER TABLE notifications
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'));

-- Partial: only live rows are ever ordered by priority. Terminal rows are the
-- overwhelming majority and would make this index mostly dead weight.
CREATE INDEX idx_notifications_priority_pending
    ON notifications (priority, created_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

COMMENT ON COLUMN notifications.priority IS 'Added in V4. Defaults to NORMAL so rows written by the pre-V4 application remain valid.';


-- -----------------------------------------------------------------------------
--  dead_letter_events
-- -----------------------------------------------------------------------------
--  Where a Kafka message goes when it cannot be processed at all.
--
--  This is NOT the same thing as a notification that failed to deliver. That
--  is a notification in FAILED status with a full attempt history — the
--  pipeline worked and the provider said no. A dead letter is a message the
--  pipeline could not handle: unparseable JSON, an event referencing a
--  notification that does not exist, a bug in the consumer. Conflating the two
--  is how real incidents get missed, so they live in separate places.
--
--  The table exists so those messages can be inspected and replayed without
--  needing Kafka tooling or shell access to the broker, which is what
--  "safe dead-letter inspection" actually requires in practice.
-- -----------------------------------------------------------------------------
CREATE TABLE dead_letter_events (
    id                 UUID         PRIMARY KEY,

    -- Where it came from, so a replay can be targeted.
    source_topic       VARCHAR(128) NOT NULL,
    source_partition   INTEGER      NULL,
    source_offset      BIGINT       NULL,

    message_key        VARCHAR(128) NULL,

    -- Stored as text, not jsonb: a message that arrives as invalid JSON is
    -- exactly the case this table is for, and jsonb would reject it on insert.
    payload            TEXT         NOT NULL,

    -- Why it could not be processed. Sanitised and truncated like every other
    -- error column in this schema.
    failure_reason     VARCHAR(500) NOT NULL,
    exception_type     VARCHAR(255) NULL,

    -- Populated when the payload was parseable enough to identify a target.
    notification_id    UUID         NULL,

    status             VARCHAR(16)  NOT NULL DEFAULT 'UNRESOLVED',
    replayed_at        TIMESTAMPTZ  NULL,
    replayed_by        BIGINT       NULL REFERENCES users (id) ON DELETE SET NULL,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_dlq_status CHECK (status IN (
        'UNRESOLVED',  -- needs a human
        'REPLAYED',    -- an administrator re-submitted it
        'DISCARDED'    -- an administrator judged it unrecoverable
    )),
    CONSTRAINT ck_dlq_replayed_at CHECK (
        (status = 'UNRESOLVED' AND replayed_at IS NULL)
        OR
        (status IN ('REPLAYED', 'DISCARDED') AND replayed_at IS NOT NULL)
    )
);

-- The operator's query: "what is waiting for me?"
CREATE INDEX idx_dlq_unresolved
    ON dead_letter_events (created_at DESC)
    WHERE status = 'UNRESOLVED';

CREATE INDEX idx_dlq_notification ON dead_letter_events (notification_id)
    WHERE notification_id IS NOT NULL;

COMMENT ON TABLE  dead_letter_events        IS 'Kafka messages that could not be PROCESSED. Distinct from notifications that failed to DELIVER — those are FAILED notifications with attempt history.';
COMMENT ON COLUMN dead_letter_events.payload IS 'Raw text, not jsonb: an unparseable payload is precisely what this table is for.';
COMMENT ON COLUMN dead_letter_events.status  IS 'UNRESOLVED until an administrator replays or discards it.';
