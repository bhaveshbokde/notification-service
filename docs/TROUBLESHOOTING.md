# Troubleshooting

Organised by symptom, because that is what you have when something is wrong.

---

## Startup

### `notification.jwt.secret must provide at least 32 bytes … got 22`

The number 22 is the giveaway: `${NOTIFICATION_JWT_SECRET}` is exactly 22
characters. The placeholder reached the key validator verbatim, which means
`.env` was never loaded.

`application.yml` imports `optional:file:./.env`, and that path resolves against
the **working directory**, not the jar.

```bash
pwd    # must be the project root
```

**From an IDE:** set the run configuration's working directory to the project
root. In IntelliJ: *Run → Edit Configurations → Working directory*.

**From the command line:** run `./mvnw spring-boot:run` from the project root,
not from `src/` or a parent.

### `A component required a bean named 'kafkaProperties' that could not be found`

A `@ConfigurationProperties` record is registered under a *qualified* bean name,
not a simple one, so `#{@kafkaProperties.topic()}` SpEL cannot resolve it. Use
`${notification.kafka.topic}` property placeholders instead. Fixed in this
codebase; noted here because the error message does not hint at the cause.

### `FlywayValidateException: Migration checksum mismatch`

An already-applied migration file was edited. Flyway checksums every file —
**including its comments**.

```
Migration checksum mismatch for migration version 3
-> Applied to database : 1245547696
-> Resolved locally    : -801750002
```

Applied migrations are immutable. Options, in order of preference:

1. **Revert the edit.** The migration goes back to matching what was applied.
2. **Put the change in a new migration** (`V5__…sql`). This is the answer for
   any real change.
3. **`./mvnw flyway:repair`** — updates the stored checksum. Only acceptable
   when the change is genuinely cosmetic *and* the migration has only ever run
   against local throwaway databases.

### `Schema-validation: missing column [x] in table [y]`

An entity declares a column the migrations do not create. `ddl-auto: validate`
caught it at startup rather than at the first query, which is the point.

Either add the column in a new migration, or remove it from the entity. Do not
switch to `ddl-auto: update` — that lets the two diverge silently and generates
different DDL in different environments.

### `Connection to 127.0.0.1:5432 refused`

```bash
pg_isready -h 127.0.0.1 -p 5432
sudo systemctl status postgresql       # or: docker compose ps postgres
```

If PostgreSQL is running but the application still cannot connect, check that
the role exists and can log in over TCP:

```bash
PGPASSWORD=$(grep '^NOTIFICATION_DB_PASSWORD=' .env | cut -d= -f2-) \
  psql -h 127.0.0.1 -U notification_app -d notification_db -c 'SELECT 1'
```

A `peer authentication failed` error means `pg_hba.conf` requires peer auth for
local connections. Add a line for `127.0.0.1` with `scram-sha-256`:

```bash
sudo grep -nE '^(host|local)' /etc/postgresql/*/main/pg_hba.conf
```

---

## Notifications are not being delivered

Work down this list; each step narrows it.

### Step 1 — what does the notification itself say?

```bash
curl -s "$B/notifications/$ID/status" -H "$AUTH" | python3 -m json.tool
```

| `status` | Meaning | Go to |
|---|---|---|
| `PENDING` | Never picked up — the event has not reached a consumer | Step 2 |
| `PROCESSING` | A consumer claimed it and has not finished | Step 5 |
| `RETRY_SCHEDULED` | A transient failure; waiting for `nextAttemptAt` | Normal. Check `attempts` |
| `SENT` | Delivered | Nothing is wrong |
| `FAILED` | Terminal. Read `lastError` and the `attempts` array | Step 6 |
| `DEAD_LETTERED` | The pipeline could not process it | Step 7 |

### Step 2 — is the outbox event published?

```bash
psql -d notification_db -c "
  SELECT status, attempt_count, claimed_by, last_error, published_at
    FROM outbox_events WHERE aggregate_id = '$ID';"
```

| `status` | Meaning |
|---|---|
| `PENDING` | Waiting for the publisher. If it stays here, go to step 3 |
| `PUBLISHING` | Claimed. If `claimed_at` is old, the publisher died — the lease will reclaim it within `lease-duration` (default 2 min) |
| `PUBLISHED` | The broker acknowledged it. The problem is downstream — go to step 4 |
| `FAILED` | Gave up after `max-publish-attempts`. **Read `last_error`** |

### Step 3 — is the publisher running and can it reach Kafka?

```bash
curl -s localhost:8080/actuator/prometheus -H "$AUTH" | grep outbox
```

```
notification_outbox_backlog 147.0          ← rising = stalled
notification_outbox_published_total 0.0    ← zero = never succeeded
notification_outbox_publish_failed_total 294.0
```

A rising backlog with a rising failure count means Kafka is unreachable:

```bash
grep "Failed to publish outbox event" logs/app.log | tail -3
~/.local/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list
```

A rising backlog with **zero** published and **zero** failed means the scheduler
is not running at all — check that `@EnableScheduling` is present and that no
exception is escaping `publishPending`.

### Step 4 — is the consumer consuming?

```bash
~/.local/opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server 127.0.0.1:9092 \
  --group notification-service --describe
```

```
TOPIC                        PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
notifications.requested.v1   0          1247            1389            142
```

A growing `LAG` means messages are arriving faster than they are processed, or
the consumer is stuck. No rows at all means the consumer group has not joined —
check the log for `partitions assigned`.

```bash
grep "partitions assigned" logs/app.log | tail -2
```

### Step 5 — notifications stuck in PROCESSING

```bash
curl -s localhost:8080/actuator/prometheus -H "$AUTH" | grep stuck_processing
```

A consumer died mid-delivery. **Nothing will move these automatically**, and
that is deliberate: the message may have reached the provider, so an automatic
retry risks a duplicate send — a second SMS to a real person.

```sql
SELECT id, recipient, updated_at, attempt_count
  FROM notifications
 WHERE status = 'PROCESSING' AND updated_at < now() - interval '15 minutes';
```

Decide per notification whether a duplicate is acceptable. If it is, move it
back to a claimable state:

```sql
UPDATE notifications
   SET status = 'RETRY_SCHEDULED', next_attempt_at = now()
 WHERE id = '…' AND status = 'PROCESSING';
```

The `RetrySweeper` will pick it up within 30 seconds.

### Step 6 — a notification FAILED

```bash
psql -d notification_db -c "
  SELECT attempt_number, outcome, error_message, duration_ms
    FROM notification_attempts WHERE notification_id = '$ID' ORDER BY attempt_number;"
```

| Pattern | Meaning |
|---|---|
| One `PERMANENT_FAILURE` | The provider rejected it outright. Check the recipient format. Retrying will not help |
| N × `TRANSIENT_FAILURE` | Retries exhausted. The provider was unavailable throughout |
| Rising `duration_ms` | The provider is degrading — look at it before the failures start |

### Step 7 — dead letters

```bash
curl -s "$B/admin/dead-letters?status=UNRESOLVED" -H "$ADMIN_AUTH" | python3 -m json.tool
```

A non-zero count means **this service** could not process a message — not that a
provider failed. Common causes:

| `failureReason` | Cause | Action |
|---|---|---|
| `could not be parsed` | A producer sent a payload this version does not understand | Fix the producer, then **discard** — there is nothing to replay to |
| `references a notification that does not exist` | The row was deleted, or an event outlived its aggregate | Discard |
| Anything else | A consumer bug | Fix it, then **replay** |

```bash
curl -s -X POST "$B/admin/dead-letters/$DLQ_ID/replay"  -H "$ADMIN_AUTH"
curl -s -X POST "$B/admin/dead-letters/$DLQ_ID/discard" -H "$ADMIN_AUTH"
```

---

## API problems

### 401 on every request

```bash
curl -s "$B/auth/me" -H "$AUTH" | python3 -m json.tool
```

| `code` | Cause |
|---|---|
| `AUTHENTICATION_REQUIRED` | No `Authorization` header, or not `Bearer <token>` |
| `TOKEN_EXPIRED` | Tokens last 60 minutes. Log in again |
| `TOKEN_INVALID` | Wrong signing key, wrong issuer, or tampered. **If the key changed, every existing token is invalid** |

A restart after regenerating `.env` invalidates every token that was issued with
the old key — expected, not a bug.

### 403 where you expected 200

You are authenticated as `ROLE_USER` and the endpoint needs `ROLE_ADMIN`
(anything under `/api/v1/admin/**`, and `/actuator/**` beyond health and info).

```bash
curl -s "$B/auth/me" -H "$AUTH"   # shows the role your token carries
```

### 404 for a notification you are sure exists

By design, a notification belonging to another caller is **indistinguishable**
from one that does not exist. Check you are using the token of the account that
submitted it, or use an `ROLE_ADMIN` token.

### 429 and you are not sending many requests

The window is per authenticated caller, and the default is 100/minute. If
several services share one account they share one budget — give them separate
accounts.

```bash
curl -s localhost:8080/actuator/prometheus -H "$ADMIN_AUTH" | grep ratelimit
```

`notification_ratelimit_failopen_total` rising means Redis is unreachable and
requests are being allowed through unchecked.

### Validation says `(redacted)` and you cannot see what was wrong

Deliberate: a recipient is personal data and error bodies end up in logs and
screenshots. The `message` field still says what was expected:

```
"must be an E.164 phone number such as +919876543210 for channel SMS"
```

---

## Tests

### Integration tests fail with `relation "notifications" does not exist`

The test database has not been migrated:

```bash
./scripts/migrate.sh test
```

### Integration tests fail on Kafka timeouts

```bash
~/.local/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list
redis-cli ping
pg_isready
```

All three must be running. `./mvnw test` (unit only) needs none of them.

### `Could not find a valid Docker environment`

Expected on a machine without a usable Docker daemon. The Testcontainers tests
are tagged and excluded from `test` and from `-Pintegration`; only
`-Pcontainers` runs them.

```bash
./mvnw verify -Pintegration     # works without Docker
./mvnw verify -Pcontainers      # needs Docker
```

### A test passes alone and fails in the suite

Almost always leaked state. The integration tests commit for real and clean up
in `AbstractIntegrationTest.resetDatabase()`; a test that creates something not
covered by that will poison the next one.

---

## Local infrastructure

### Starting the services without Docker

```bash
# PostgreSQL
sudo systemctl start postgresql

# Kafka (KRaft, single node)
~/.local/opt/kafka/bin/kafka-server-start.sh -daemon \
  ~/.local/opt/<data-dir>/kafka/server.properties

# Redis
~/.local/opt/redis/bin/redis-server --daemonize yes
```

### Checking everything is up

```bash
pg_isready -h 127.0.0.1 -p 5432
~/.local/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list
redis-cli ping
```

### Stopping the application cleanly

```bash
# Kill by port owner, not by command-line pattern: `pkill -f spring-boot:run`
# matches the shell that contains the pattern and kills it too.
kill $(ss -lptn 'sport = :8080' | grep -oP 'pid=\K[0-9]+' | head -1)
```

Graceful shutdown is configured, so in-flight Kafka offsets commit before exit.

### Resetting to a clean state

```bash
psql -d notification_db <<'SQL'
DELETE FROM dead_letter_events;
DELETE FROM idempotency_records;
DELETE FROM outbox_events;
DELETE FROM notification_attempts;
DELETE FROM notifications;
SQL
```

Order matters — it follows the foreign keys from child to parent. Leaving
`users` alone keeps the bootstrapped account.

---

## Reading the logs

Every line carries the MDC:

```
2026-10-15T17:09:28.501+05:30  INFO [4ca5b243493b4a3a,01a13f5c-…]  DeliveryProcessor : …
                                     └─ traceId      └─ notificationId
```

Follow one notification through the whole asynchronous pipeline:

```bash
grep "01a13f5c-2a33-71ca-a5fb-31676338dcdc" logs/app.log
```

Follow one HTTP request:

```bash
grep "4ca5b243493b4a3a" logs/app.log
```

The `traceId` is also returned in the `X-Trace-Id` header and in every error
body, so a user-reported failure can be found exactly — without a stack trace
ever reaching the client.

> The trace id does not survive the Kafka boundary; `notificationId` is what
> correlates the asynchronous half. That is a deliberate limitation, not an
> oversight — propagating it would mean putting it in the event payload, and
> the payload is deliberately minimal.
