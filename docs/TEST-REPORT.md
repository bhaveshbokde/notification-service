# Test Report

What was executed, what it found, and what is deliberately not covered.

Every number here was produced by running the suite. Nothing is estimated, and
the gaps are listed as explicitly as the passes.

---

## Summary

| | Tests | Failures | Errors | Skipped | Duration |
|---|---:|---:|---:|---:|---:|
| Unit (Surefire) | 166 | 0 | 0 | 0 | 5.2 s |
| Integration, security, concurrency (Failsafe) | 89 | 0 | 0 | 0 | 95.8 s |
| **Total** | **255** | **0** | **0** | **0** | **~101 s** |

```bash
./mvnw clean test              # the 166 unit tests — no infrastructure
./mvnw verify -Pintegration    # all 255 — needs PostgreSQL, Kafka and Redis
```

### Coverage (JaCoCo)

| Metric | Covered / Total | % |
|---|---|---:|
| Instruction | 6 078 / 7 715 | 78.8% |
| **Line** | 1 350 / 1 663 | **81.2%** |
| Branch | 250 / 443 | 56.4% |
| Method | 369 / 465 | 79.4% |
| Class | 73 / 73 | **100%** |

DTOs are excluded — they are records whose accessors and `equals`/`hashCode` are
compiler-generated, and counting them inflates the number without saying
anything about the tests. **Entities are deliberately not excluded**:
`Notification` carries the state machine and `NotificationStatus` carries the
transition table, which is the most logic-dense code in the project. Excluding
them would hide exactly what the number is meant to report.

---

## Per-class results

### Unit tests

| Class | Tests | Duration |
|---|---:|---:|
| `notification.domain.NotificationChannelTest` | 52 | 0.2 s |
| `notification.domain.NotificationStatusTest` | 47 | 0.1 s |
| `notification.domain.NotificationTest` | 20 | 0.1 s |
| `notification.service.NotificationServiceTest` | 20 | 1.5 s |
| `delivery.RetryPolicyTest` | 17 | 0.5 s |
| `ratelimit.RateLimiterTest` | 10 | 2.6 s |
| **Total** | **166** | **5.2 s** |

### Integration tests

| Class | Tests | Duration | What it exercises |
|---|---:|---:|---|
| `security.SecurityIT` | 35 | 40.6 s | 401/403/404, token forgery, injection, data isolation |
| `notification.NotificationApiIT` | 28 | 18.3 s | Full async pipeline, retry, exhaustion, idempotency, filtering |
| `deadletter.DeadLetterIT` | 13 | 14.4 s | Recording, inspection, replay, authorization |
| `outbox.OutboxTransactionIT` | 8 | 19.2 s | Atomicity, rollback, SKIP LOCKED, lease recovery |
| `idempotency.IdempotencyConcurrencyIT` | 5 | 3.3 s | 12 simultaneous requests with one key |
| **Total** | **89** | **95.8 s** | |

### Coverage by package

| Package | Line |
|---|---:|
| `auth/service` | 96.0% |
| `config` | 89.1% |
| `notification/service` | 88.3% |
| `notification/domain` | 87.8% |
| `provider/simulated` | 87.7% |
| `delivery` | 85.8% |
| `security` | 85.3% |
| `idempotency/service` | 81.1% |
| `messaging` | 76.1% |
| `outbox/service` | 72.9% |
| `common/exception` | 71.3% |

---

## Bugs the tests found

The honest value of a test suite is what it catches. These are real defects in
this codebase, each found by a specific test rather than by review.

### 1. `UnexpectedRollbackException` — ten of twelve concurrent requests returned 500

**Found by:** `IdempotencyConcurrencyIT.sameKeyCreatesOneNotification`. Every
single-threaded test passed throughout, because single-threaded there is no race
to lose.

Twelve simultaneous requests with the same idempotency key produced **one 202,
one 409 and ten 500s**. The unique index was working perfectly — exactly one
notification was created — but almost every loser got a server error instead of
a usable answer.

`IdempotencyStore.claim` was written the natural way:

```java
@Transactional(propagation = REQUIRES_NEW)
public Optional<IdempotencyRecord> claim(...) {
    try {
        return Optional.of(repository.saveAndFlush(record));
    } catch (DataIntegrityViolationException duplicate) {
        return Optional.empty();        // keeps the race on the control-flow path
    }
}
```

The constraint violation marks the transaction **rollback-only**. Catching the
exception does not un-mark it — the mark lives on the transaction, not on the
exception. So the method returned normally, Spring's interceptor tried to
commit, and the commit failed with `UnexpectedRollbackException: Transaction
silently rolled back because it has been marked as rollback-only`, which escaped
as a 500.

**Fix:** let the exception propagate out of the `REQUIRES_NEW` method so the
inner transaction rolls back cleanly, and catch it in `IdempotencyService` —
which runs outside that transaction.

**Now covered by:** the test that found it, plus
`IdempotencyConcurrencyIT.losersGetActionableResponses` which asserts no 500
appears at all.

### 2. The time-ordered id ignored the supplied clock

**Found by:** `NotificationTest.idsAreTimeOrdered`.

`Notification.create(..., Instant now)` accepted a timestamp and then generated
the id from `System.currentTimeMillis()` — so the timestamp embedded in the id
and the entity's own `createdAt` could disagree. With a fixed test clock the ids
were not orderable at all, and in production the id's ordering property would
have been quietly wrong whenever the application clock was not the wall clock.

**Fix:** derive the id from the supplied instant (`Ids.timeOrdered(Instant)`).

### 3. Bean-name SpEL in `@KafkaListener` broke startup

**Found by:** the first attempt to start the application.

```java
@KafkaListener(topics = "#{@kafkaProperties.topic()}")
```

A `@ConfigurationProperties` record is registered under a *qualified* bean name
— `notification.kafka-com.bhavesh.notification.config.KafkaProperties` — not a
simple one. Startup failed with *"A component required a bean named
'kafkaProperties' that could not be found."*

**Fix:** `${notification.kafka.topic}` property placeholders, which read the
same values straight from the environment with no bean-name ambiguity.

---

## What the tests actually assert

### The outbox

`OutboxTransactionIT` is the test a mock cannot replace.
`NotificationServiceTest` proves the service writes both rows; only a real
database can prove they share a transaction.

| Test | Asserts |
|---|---|
| `bothRowsCommitTogether` | The event exists the moment the notification does |
| `rollbackRemovesBoth` | A failure after both writes leaves **neither** — no orphaned event |
| `publisherDrainsTheOutbox` | The event reaches `PUBLISHED` |
| `publishedAtProvesAcknowledgement` | `published_at` is set — so `PUBLISHED` means "the broker has it", not "we asked" |
| `concurrentPublishersDoNotOverlap` | 4 publishers, 40 events, **no duplicate claims** — `SKIP LOCKED` works |
| `expiredLeaseIsReclaimed` | A 10-minute-old claim is recovered and published |
| `freshLeaseSurvives` | A seconds-old claim is **not** stolen — stealing it would publish twice |
| `backlogCountsUnpublished` | The gauge tracks and returns to zero |

### Idempotency under concurrency

Threads are released simultaneously by a `CyclicBarrier`. Threads started in a
loop do not run at the same time — the first may finish before the last begins,
and the test would pass without ever creating the race it claims to test.

| Test | Setup | Asserts |
|---|---|---|
| `sameKeyCreatesOneNotification` | 12 simultaneous, one key | Exactly **1** notification, 1 outbox event, exactly one 202 |
| `losersGetActionableResponses` | same | **Zero 500s**; every loser gets 200 or 409 |
| `noKeyMeansNoDeduplication` | 12 simultaneous, no key | 12 notifications — correct, the caller opted out |
| `keysAreScopedPerCallerUnderConcurrency` | 2 callers, same key string | 2 notifications — no cross-caller collision |
| `uniqueIndexIsTheBackstop` | Direct SQL insert | Refused by `uq_idempotency_user_endpoint_key` |

### The delivery pipeline

| Test | Asserts |
|---|---|
| `deliversEndToEnd` | PENDING → SENT with an attempt row and a provider reference |
| `retriesTransientFailure` | 2 attempts recorded: `TRANSIENT_FAILURE` then `SUCCESS` |
| `exhaustsRetries` | 3 attempts, then `FAILED` with "Retries exhausted" |
| `doesNotRetryPermanentFailure` | **1** attempt, not 3 |
| `recordsLifecycleTimestamps` | `processingStartedAt` and `completedAt` both set |

### Security

35 tests probing what an attacker would: `alg:none` forgery claiming
`ROLE_ADMIN`, tampered tokens, malformed `Authorization` headers, SQL
metacharacters in the username, and — the one worth reading —
`existenceIsNotLeaked`, which asserts that a 404 for another caller's
notification is byte-comparable in code, status and field set with a 404 for an
id that does not exist.

### Content safety

Asserted rather than assumed, in several places:

- `NotificationServiceTest.payloadCarriesNoContent` — the Kafka payload contains
  no recipient and no body.
- `NotificationApiIT.doesNotLeakContent` — the API response contains neither.
- `NotificationTest.toStringIsSafeForLogging` — `toString` leaks neither.
- `NotificationApiIT.redactsRejectedRecipient` — a validation error says
  `(redacted)`, not the phone number.

---

## Test design decisions

**Integration tests are not `@Transactional`.** The behaviour under test *is*
transactional: the outbox depends on two writes committing together, and the
idempotency claim depends on `REQUIRES_NEW` committing independently. An outer
transaction would hide those boundaries, swallow commit-time constraint
violations, and make the concurrency tests meaningless — a second thread cannot
see an uncommitted row.

**Jitter is set to zero in tests.** That makes `RetryPolicy.nextDelay` a pure
function of the attempt number, so delays are exactly assertable. Jitter itself
is tested separately and directly, including that it stays within its band and
that the cap is applied before it rather than after.

**No `Thread.sleep` in assertions.** Awaitility polls with a timeout. A sleep
long enough to be reliable makes the suite slow, and one short enough to be fast
makes it flaky on a loaded CI machine.

**MockMvc, not a random port.** It exercises the whole web layer — filter chain,
dispatcher, validation, exception handling, JSON — without binding a socket.
One consequence worth knowing: `springSecurity()` registers only the security
chain, so `TraceIdFilter` has to be added by hand or `X-Trace-Id` is missing in
tests while working perfectly in production.

**Database constraints were verified directly.** Before any Java ran, a SQL
probe asserted that the schema rejects: an invalid status, a terminal status
with no `completed_at`, a non-terminal status *with* one, a duplicate attempt
number, a `PUBLISHED` outbox row with no `published_at`, a duplicate idempotency
key, and a malformed request hash. All seven were refused.

---

## End-to-end verification (manual, against live infrastructure)

Run against PostgreSQL 16.15, Kafka 3.9.0 (KRaft) and Redis 7.4.1 on the host.
Captured output is in [API-EXAMPLES.md](API-EXAMPLES.md).

| # | Scenario | Result |
|---|---|---|
| 1 | Unauthenticated request | 401 `AUTHENTICATION_REQUIRED` |
| 2 | Login | 200, token, `ROLE_ADMIN` |
| 3 | Submit EMAIL notification | 202, `PENDING`, recipient masked, `simulated: true` |
| 4 | Pipeline completes | `SENT` in ~1.4 s |
| 5 | Outbox | `PUBLISHED` with `published_at` set |
| 6 | Attempt history | 1 × `SUCCESS`, provider ref, 53 ms |
| 7 | Same key, same body | **200** (replay), same id, still 1 row |
| 8 | Same key, different body | **409** `IDEMPOTENCY_KEY_REUSED` |
| 9 | Flaky recipient | `RETRY_SCHEDULED` → `SENT`, 2 attempts |
| 10 | Transient recipient | 3 attempts → `FAILED`, "Retries exhausted" |
| 11 | Permanent recipient | **1** attempt → `FAILED` |
| 12 | Validation (4 cases) | 400 with field-level errors, recipient redacted |
| 13 | Rate limiting | 98 × 202 then 17 × 429 with `Retry-After` |
| 14 | **Restart recovery A** | A committed-but-unpublished event published after restart |
| 15 | **Restart recovery B** | A 10-min-old `PUBLISHING` lease reclaimed, logged, published |
| 16 | Kafka topics | 3 created; FinSight's 2 topics on the shared broker untouched |

Scenarios 14 and 15 are the ones that matter most: they prove the outbox's
durability claim rather than asserting it.

---

## What is NOT covered, and why

Stated so the gaps read as decisions.

| Gap | Reason |
|---|---|
| **Testcontainers tests** | Tagged `testcontainers` and excluded from every default run. The development machine has no usable Docker daemon — the socket is `root:docker 0660`, the user is not in the `docker` group, and rootless is blocked by a missing `uidmap` — so **they have never been executed here**. CI has Docker and runs them. Stated plainly rather than implied by an absent tag. |
| **`docker-compose.yml`** | Same reason. It parses and interpolates (`docker compose config` succeeds), and it is written to each image's documented behaviour, but it **has not been run**. |
| **Load / soak testing** | The concurrency tests prove correctness under contention, not throughput under sustained load. Bottlenecks are reasoned about in [ARCHITECTURE.md §11](ARCHITECTURE.md#11-scalability-bottlenecks) rather than measured. |
| **A real email or SMS provider** | There isn't one. The adapters are simulated by design. |
| **Multi-instance behaviour** | Every guarantee is enforced by the database rather than JVM state, which is what makes it survive multiple instances — but no test runs two application processes. The `SKIP LOCKED` test uses concurrent threads sharing one process. |
| **Branch coverage below 70%** | 56.4%. The uncovered branches are mostly logging guards, null-defence on paths the type system already constrains, and error handlers that need a mid-request Kafka or Redis outage to reach. Chasing them would mean tests that assert on log output. |
| **`deadletter/domain` and `outbox/domain` getters** | Plain accessors on entities, exercised indirectly through the API. Low value to test directly. |
| **Flyway rollback** | Flyway Community has no `undo`. Forward-only is the design; a change goes in a new migration. |

---

## Reproducing

```bash
# Unit only — no external services
./mvnw clean test

# Everything — needs PostgreSQL, Kafka and Redis
./scripts/migrate.sh test        # once, to prepare notification_test_db
./mvnw verify -Pintegration

# A single class
./mvnw verify -Pintegration -Dit.test=OutboxTransactionIT -Dtest=RetryPolicyTest

# With Testcontainers, if Docker is available
./mvnw verify -Pcontainers
```

Reports land in `target/surefire-reports/`, `target/failsafe-reports/` and
`target/site/jacoco/index.html`.

The integration tests `DELETE` between classes, which is exactly why they point
at `notification_test_db` rather than the development database.
