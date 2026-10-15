# API Examples

A copy-pasteable walkthrough of the whole pipeline, including the failure paths
— which are the interesting half.

**Every response below was captured from a running instance.** The ids,
provider references and timestamps are real ones from the session that produced
this file; yours will differ.

---

## Setup

```bash
sudo bash scripts/provision-db.sh     # once
./scripts/migrate.sh                  # once
./mvnw spring-boot:run
```

Then, in another shell:

```bash
export B=http://localhost:8080/api/v1
```

> **Prefer a browser?** <http://localhost:8080/swagger-ui.html> has an
> *Authorize* button — paste the `accessToken` from step 1.

---

## 1. Authenticate

The migrations seed **zero users**, so no credential is in version control. The
development account is created at startup from `.env`:

```bash
export USER=$(grep '^NOTIFICATION_ADMIN_USERNAME=' .env | cut -d= -f2-)
export PASS=$(grep '^NOTIFICATION_ADMIN_PASSWORD=' .env | cut -d= -f2-)

export TOKEN=$(curl -s -X POST "$B/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

export AUTH="Authorization: Bearer $TOKEN"
```

> Reading the values out of `.env` keeps them off your shell history. They never
> appear in a command line, where `ps` would show them to every user on the
> machine.

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "username": "admin",
  "role": "ROLE_ADMIN"
}
```

### Without a token

```bash
curl -s "$B/notifications" | python3 -m json.tool
```

```json
{
  "type": "https://api.notification.local/problems/authentication-required",
  "title": "Authentication Required",
  "status": 401,
  "detail": "Authentication is required to access this resource",
  "instance": "/api/v1/notifications",
  "code": "AUTHENTICATION_REQUIRED",
  "traceId": "c7c9b8ffbeca480e",
  "timestamp": "2026-10-15T11:38:14.794Z"
}
```

---

## 2. Submit a notification

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-key-001' \
  -d '{
    "channel": "EMAIL",
    "recipient": "priya@example.com",
    "subject": "Your verification code",
    "body": "Your code is 418290.",
    "priority": "HIGH",
    "metadata": {"template": "otp-v2"}
  }' | python3 -m json.tool
```

**HTTP 202 Accepted**

```json
{
  "id": "01a13f5c-2a33-71ca-a5fb-31676338dcdc",
  "channel": "EMAIL",
  "status": "PENDING",
  "priority": "HIGH",
  "recipient": "p***a@example.com",
  "subject": "Your verification code",
  "metadata": "{\"template\":\"otp-v2\"}",
  "attemptCount": 0,
  "maxAttempts": 3,
  "createdAt": "2026-10-15T11:39:27.154871210Z",
  "updatedAt": "2026-10-15T11:39:27.154871210Z",
  "simulated": true
}
```

Four things to notice:

- **202, not 201.** Nothing has been delivered. The notification is recorded and
  an outbox event is committed, and that is all.
- **The recipient is masked.** The body is absent entirely. You supplied both
  and already know them; returning them would mean anyone who could read this
  notification could read the one-time code it carried.
- **`"simulated": true`.** No email was sent, and the API says so rather than
  letting you assume otherwise.
- **The id is a UUIDv7** (`…-71ca-…`), time-ordered so inserts do not scatter
  the primary-key index.

```bash
export NID=$(... | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
```

---

## 3. Watch the pipeline complete

```bash
curl -s "$B/notifications/$NID/status" -H "$AUTH" | python3 -m json.tool
```

```json
{
  "id": "01a13f5c-2a33-71ca-a5fb-31676338dcdc",
  "status": "SENT",
  "terminal": true,
  "attemptCount": 1,
  "maxAttempts": 3,
  "createdAt":           "2026-10-15T11:39:27.154871Z",
  "processingStartedAt": "2026-10-15T11:39:28.501311Z",
  "completedAt":         "2026-10-15T11:39:28.564961Z",
  "attempts": [
    {
      "attemptNumber": 1,
      "outcome": "SUCCESS",
      "providerReference": "SIM-EMAIL-74F72764",
      "durationMs": 53,
      "createdAt": "2026-10-15T11:39:28.564961Z"
    }
  ],
  "simulated": true
}
```

End to end in **1.4 seconds**: accepted at `:27.154`, picked up at `:28.501`,
delivered at `:28.564`.

`terminal` tells a polling client when to stop, rather than requiring it to
hard-code a list of terminal statuses that will drift.

### Confirm the database agrees

```bash
psql -d notification_db -c "
  SELECT n.status, n.attempt_count, o.status AS outbox, o.published_at IS NOT NULL AS acked
    FROM notifications n JOIN outbox_events o ON o.aggregate_id = n.id
   WHERE n.id = '$NID';"
```

```
 status | attempt_count |  outbox   | acked
--------+---------------+-----------+-------
 SENT   |             1 | PUBLISHED | t
```

`published_at` being set is the proof that the broker *acknowledged* the event,
not merely that we asked it to.

---

## 4. Idempotency

### Same key, same body → replay

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: e2e-key-001' \
  -d '{"channel":"EMAIL","recipient":"priya@example.com","subject":"Your verification code","body":"Your code is 418290.","priority":"HIGH","metadata":{"template":"otp-v2"}}' \
  -o /dev/null -w "%{http_code}\n"
```

```
200
```

**200, not 202** — nothing was accepted this time. The body is the original
response, and `SELECT COUNT(*) FROM notifications` is still 1.

### Same key, different body → 409

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: e2e-key-001' \
  -d '{"channel":"SMS","recipient":"+919876543210","body":"A completely different message"}' \
  | python3 -m json.tool
```

```json
{
  "status": 409,
  "code": "IDEMPOTENCY_KEY_REUSED",
  "detail": "This Idempotency-Key was already used with a different request body"
}
```

Silently replaying the first response here would make a genuine second
notification disappear — which, for a password reset, means the user never gets
their email.

---

## 5. Retry — a transient failure that recovers

The simulated adapters script their outcome from the recipient, so every failure
path is reachable through the ordinary API with no test-only request field.

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"IN_APP","recipient":"flaky-user-77","body":"retry probe"}'
```

Polling the status:

```
t+1s: PENDING          attempts=0
t+2s: RETRY_SCHEDULED  attempts=1
t+3s: RETRY_SCHEDULED  attempts=1
t+4s: SENT             attempts=2   terminal
```

```bash
psql -d notification_db -c "
  SELECT attempt_number, outcome, error_message
    FROM notification_attempts WHERE notification_id='$RID' ORDER BY attempt_number;"
```

```
 attempt_number |      outcome      |                  error_message
----------------+-------------------+--------------------------------------------------
              1 | TRANSIENT_FAILURE | Simulated transient failure on attempt 1 (flaky…)
              2 | SUCCESS           |
```

Both attempts are kept. A single `lastError` column would have been overwritten
by attempt 2 and the failure would be unexplainable afterwards.

---

## 6. Retry exhaustion

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"IN_APP","recipient":"transient-user-88","body":"exhaustion probe","maxAttempts":3}'
```

```
t+3s: RETRY_SCHEDULED  attempts=1
t+6s: RETRY_SCHEDULED  attempts=2
t+8s: FAILED           attempts=3   terminal
```

```
 attempt_number |      outcome
----------------+-------------------
              1 | TRANSIENT_FAILURE
              2 | TRANSIENT_FAILURE
              3 | TRANSIENT_FAILURE

last_error: Retries exhausted after 3 attempts: Simulated transient failure:
            provider temporarily unavailable
```

Note the backoff in the timings: ~2s then ~4s. And note the error distinguishes
*exhaustion* from a permanent rejection — "failed" alone would send an operator
looking in the wrong place.

---

## 7. Permanent failure — not retried

```bash
curl -s -X POST "$B/notifications" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"IN_APP","recipient":"permanent-user-99","body":"permanent probe"}'
```

```
status: FAILED   attempts: 1 / 3
  1  PERMANENT_FAILURE  Simulated permanent failure: recipient rejected by provider
```

**One attempt, not three.** Retrying a malformed recipient produces the identical
answer and, on a paid provider, costs money each time.

### The full outcome matrix

| Recipient prefix | Behaviour |
|---|---|
| `success…` | delivers on attempt 1 |
| `flaky…` | fails once, then succeeds |
| `transient…` | fails retryably every time → exhaustion |
| `permanent…` | fails permanently → no retry |
| anything else | delivers |

---

## 8. Validation

```bash
# An email address on an SMS channel
curl -s -X POST "$B/notifications" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"channel":"SMS","recipient":"not-a-phone-number","body":"x"}' | python3 -m json.tool
```

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "errors": [
    {
      "field": "recipient",
      "rejectedValue": "(redacted)",
      "message": "must be an E.164 phone number such as +919876543210 for channel SMS"
    }
  ]
}
```

The rejected value is **redacted**. A recipient is personal data and validation
bodies end up in logs, bug reports and screenshots.

```bash
# EMAIL without a subject
-d '{"channel":"EMAIL","recipient":"a@b.co","body":"x"}'
#   → subject → is required for channel EMAIL

# An unknown channel — the error lists the valid ones
-d '{"channel":"CARRIER_PIGEON","recipient":"a@b.co","body":"x"}'
#   → "Invalid value for field 'channel'; expected one of [EMAIL, SMS, IN_APP]"

# Malformed JSON
-d '{"channel":"EMAIL","body":'
#   → MALFORMED_REQUEST, and the body is NOT echoed back
```

---

## 9. Listing and filtering

```bash
curl -s "$B/notifications?size=5" -H "$AUTH"
curl -s "$B/notifications?channel=SMS" -H "$AUTH"
curl -s "$B/notifications?status=FAILED" -H "$AUTH"
curl -s "$B/notifications?createdAfter=2026-10-15T00:00:00Z" -H "$AUTH"
```

All filters are optional and combine. Results are scoped to the calling account
unless it is an administrator.

```json
{
  "content": [ ... ],
  "page": 0, "size": 5,
  "totalElements": 18, "totalPages": 4,
  "first": true, "last": false, "empty": false
}
```

An inverted date range is rejected rather than silently returning nothing:

```
400  INVALID_PARAMETER  "createdAfter must not be later than createdBefore"
```

---

## 10. Rate limiting

```bash
for i in $(seq 1 115); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$B/notifications" -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"channel":"IN_APP","recipient":"ratelimit-probe","body":"x"}'
done | sort | uniq -c
```

```
     98 202
     17 429
```

```json
{
  "status": 429,
  "code": "RATE_LIMIT_EXCEEDED",
  "detail": "Rate limit of 100 requests exceeded; retry in 40 second(s)"
}
```

```
Retry-After: 60
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
```

---

## 11. Dead letters (administrator)

A dead letter is a message the pipeline could not **process** — unparseable
JSON, an event naming a notification that does not exist. It is not a failed
delivery; those are FAILED notifications with a full attempt history.

```bash
curl -s "$B/admin/dead-letters/count" -H "$AUTH"
# {"unresolved": 0}

curl -s "$B/admin/dead-letters?status=UNRESOLVED" -H "$AUTH" | python3 -m json.tool
```

```json
{
  "content": [{
    "id": "01a13f77-…",
    "sourceTopic": "notifications.requested.v1",
    "sourcePartition": 0,
    "sourceOffset": 42,
    "payload": "{this is not json",
    "failureReason": "Event payload could not be parsed: JsonParseException",
    "status": "UNRESOLVED",
    "replayable": false
  }],
  "totalElements": 1
}
```

This is the one endpoint that returns raw message content. It is `ADMIN`-only,
and an operator deciding whether to replay or discard cannot do so without
seeing what the message actually contains.

```bash
curl -s -X POST "$B/admin/dead-letters/$DLQ_ID/replay"  -H "$AUTH"
curl -s -X POST "$B/admin/dead-letters/$DLQ_ID/discard" -H "$AUTH"
```

Replaying twice is refused (`422 NOTIFICATION_NOT_REPLAYABLE`) — which is what
stops two operators working the same backlog from enqueuing it twice.

---

## 12. Proving the outbox survives a restart

The durability claim, demonstrated rather than asserted.

```bash
# 1. Stop the application.
kill $(ss -lptn 'sport = :8080' | grep -oP 'pid=\K[0-9]+' | head -1)

# 2. While it is DOWN, create a notification and its outbox event by hand —
#    simulating one that committed but was never published.
psql -d notification_db <<'SQL'
  INSERT INTO notifications (id, channel, status, recipient, body, created_by, max_attempts, priority)
  VALUES ('…', 'IN_APP', 'PENDING', 'recovery-probe-A', 'committed but never published', 1, 3, 'NORMAL');

  INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, message_key, payload, status)
  VALUES (gen_random_uuid(), 'Notification', '…', 'NotificationRequested',
          'notifications.requested.v1', '…', '{…}'::jsonb, 'PENDING');
SQL

# 3. And one claimed by a publisher that then died.
psql -d notification_db -c "
  INSERT INTO outbox_events (…, status, claimed_at, claimed_by)
  VALUES (…, 'PUBLISHING', now() - interval '10 minutes', 'dead-publisher-abc123');"

# 4. Restart.
./mvnw spring-boot:run
```

```
t+1s: PUBLISHING/PENDING   PUBLISHING/PENDING
t+2s: PUBLISHED/SENT       PUBLISHED/SENT
```

And in the log:

```
WARN  c.b.n.outbox.service.OutboxPublisher : Reclaimed 1 outbox event(s) from
      expired publisher leases — an instance likely died mid-batch
```

Both recovered and delivered within two seconds of startup. The first is the
case the outbox exists for; the second is the lease mechanism doing its job.

---

## 13. Security probes

```bash
# Another caller's notification → 404, not 403
curl -s "$B/notifications/$SOMEONE_ELSES_ID" -H "Authorization: Bearer $OTHER_TOKEN"
#   404 RESOURCE_NOT_FOUND — identical to an id that does not exist

# An ordinary caller reaching an admin endpoint → 403, not 401
curl -s "$B/admin/dead-letters" -H "Authorization: Bearer $USER_TOKEN"
#   403 ACCESS_DENIED

# A tampered token
curl -s "$B/notifications" -H "Authorization: Bearer ${TOKEN%?}X"
#   401 TOKEN_INVALID

# An alg:none forgery claiming ROLE_ADMIN
HDR=$(printf '{"alg":"none","typ":"JWT"}' | base64 | tr -d '=' | tr '/+' '_-')
PLD=$(printf '{"sub":"1","rol":"ROLE_ADMIN","usr":"forged","iss":"notification-service","exp":9999999999}' \
      | base64 | tr -d '=\n' | tr '/+' '_-')
curl -s "$B/admin/dead-letters" -H "Authorization: Bearer $HDR.$PLD."
#   401 TOKEN_INVALID
```

---

## 14. Health and metrics

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/prometheus -H "$AUTH" | grep notification_
```

The metrics worth watching:

```
notification_outbox_backlog              # rising = publisher stalled
notification_deadletter_unresolved       # non-zero = THIS SERVICE is broken
notification_stuck_processing            # consumers died mid-delivery
notification_outbox_leases_recovered     # non-zero = an instance died
notification_delivery_sent_total
notification_delivery_failed_total
notification_delivery_retried_total
notification_ratelimit_failopen_total    # Redis was down
```

---

## Error reference

| Code | Status | Means |
|---|---|---|
| `VALIDATION_FAILED` | 400 | See the `errors` array |
| `MALFORMED_REQUEST` | 400 | Body could not be parsed |
| `INVALID_PARAMETER` | 400 | A query or path parameter is wrong |
| `AUTHENTICATION_REQUIRED` | 401 | No usable credential |
| `INVALID_CREDENTIALS` | 401 | Wrong username **or** password — deliberately the same |
| `TOKEN_EXPIRED` | 401 | Re-authenticate |
| `TOKEN_INVALID` | 401 | Signature, issuer or shape wrong |
| `ACCESS_DENIED` | 403 | Authenticated, not permitted |
| `RESOURCE_NOT_FOUND` | 404 | Does not exist, **or** is not yours |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Wrong `Content-Type` |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same key, different request |
| `CONCURRENT_MODIFICATION` | 409 | Lost a race, or a duplicate is in flight — retry |
| `NOTIFICATION_NOT_REPLAYABLE` | 422 | Already resolved, or nothing to replay to |
| `RATE_LIMIT_EXCEEDED` | 429 | Carries `Retry-After` |
| `INTERNAL_ERROR` | 500 | A bug. Quote the `traceId`. |
| `DEPENDENCY_UNAVAILABLE` | 503 | Database, Kafka or Redis unreachable |

Every response — success or failure — carries `X-Trace-Id`, matching the
`traceId` in the body and in the server log.
