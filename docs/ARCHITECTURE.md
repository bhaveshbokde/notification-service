# Architecture

Why this service is shaped the way it is, and what each decision was chosen
*against*.

---

## Contents

1. [Why a modular monolith](#1-why-a-modular-monolith)
2. [Why Kafka](#2-why-kafka)
3. [The transactional outbox](#3-the-transactional-outbox)
4. [Transaction boundaries](#4-transaction-boundaries)
5. [Idempotency and duplicate processing](#5-idempotency-and-duplicate-processing)
6. [Retry and dead-letter decisions](#6-retry-and-dead-letter-decisions)
7. [Concurrency and race conditions](#7-concurrency-and-race-conditions)
8. [Redis, and its limits](#8-redis-and-its-limits)
9. [Security](#9-security)
10. [Failure scenarios and recovery](#10-failure-scenarios-and-recovery)
11. [Scalability bottlenecks](#11-scalability-bottlenecks)

---

## 1. Why a modular monolith

One deployable unit, organised by feature:

```
notification/  outbox/  messaging/  delivery/  provider/
idempotency/   deadletter/  ratelimit/  security/  user/  auth/  common/  config/
```

### Why not microservices

The obvious split would be "API service" and "delivery worker". It would be
wrong here, and the reason is the outbox: the whole point of that pattern is
that **writing the notification and publishing its event must share a
transaction**. Split them across a process boundary and you have reintroduced
exactly the dual-write problem you built the outbox to solve, except now it
spans a network call.

The other usual arguments do not apply either:

- *Independent scaling* — the API and the consumer already scale independently
  inside one deployable, because the consumer is driven by Kafka partitions
  rather than by HTTP traffic. Running six instances scales both.
- *Independent deployment* — one team, one release cadence. The coordination
  cost would be pure overhead.
- *Fault isolation* — the database is the shared dependency either way.

### Why organise by feature rather than by layer

`controller/`, `service/`, `repository/` at the top level means every change to
one feature touches four distant directories, and nothing stops the delivery
processor reaching into the idempotency repository because they are neighbours.
Organising by feature keeps a change local and makes an unwanted dependency
visible as an unusual import.

The seams are real. If delivery ever needs its own scaling profile or its own
database, `delivery/` plus `provider/` is already a coherent unit.

---

## 2. Why Kafka

The requirement is that accepting a notification is fast and delivering it is
not. A provider call takes 50ms on a good day and 30 seconds on a bad one; doing
it inside the HTTP request would mean the caller's timeout is governed by
someone else's infrastructure.

So the work is queued. The question is what with.

| Option | Why not |
|---|---|
| **A database table as a queue** | Workable, and the outbox is already one. But polling for *delivery* work means every consumer instance polls, and `SKIP LOCKED` contention grows with instance count. Kafka's partition assignment does that distribution for free. |
| **RabbitMQ** | A good fit, and simpler to operate. Kafka's retention is what tips it: a message stays readable after being consumed, so a bug can be fixed and the partition replayed from an offset. With Rabbit, an acknowledged message is gone. |
| **Redis lists / Streams** | Fast, and Redis is already here for rate limiting. But then the delivery pipeline's durability depends on a cache configured with `allkeys-lru` eviction — which is precisely the wrong trade for work that must not be lost. |
| **An in-process `ExecutorService`** | No durability at all: a restart loses every queued notification. |

Kafka also gives **ordering per key**. Events are keyed by notification id, so a
retry event cannot be consumed before the original request for the same
notification.

### What Kafka does *not* give

Exactly-once delivery to an external provider. Kafka's exactly-once semantics
cover reading-processing-writing *within* Kafka and a transactional sink. An
SMS gateway is neither. This is covered in [§5](#5-idempotency-and-duplicate-processing).

---

## 3. The transactional outbox

### The failure it prevents

```java
@Transactional
void create(request) {
    notificationRepository.save(notification);   // committed
    kafkaTemplate.send(topic, event);            // and then?
}
```

Three ways this loses a notification:

1. **The broker is down.** The save commits; the send fails; nothing retries it.
2. **The send is asynchronous.** `send()` returns a future and returns
   *immediately*. The method commits and returns 202 for an event that may never
   reach the broker.
3. **The process dies in between.** Same outcome.

Reversing the order does not help: publish-then-save means a consumer can
receive an event for a notification that does not exist, and the save can still
fail.

### The mechanism

The event is written to `outbox_events` **in the same transaction** as the
notification. Both commit or neither does. A separate publisher then:

1. **Recovers** — returns rows whose publisher lease expired to `PENDING`.
2. **Claims** — `SELECT … FOR UPDATE SKIP LOCKED`, stamps them `PUBLISHING`,
   commits immediately.
3. **Publishes** — sends each event and **blocks on the broker's
   acknowledgement**, outside any transaction.
4. **Records** — marks `PUBLISHED` in a fresh short transaction per event.

Step 3 must not be inside a transaction. Holding a database connection across a
network call means a slow broker exhausts the connection pool and takes the API
down with it.

### Why `.get()` and not a callback

```java
kafkaTemplate.send(...).get(45, TimeUnit.SECONDS);   // blocks
markPublished(event.getId());
```

Marking the row published when `send()` returns would defeat the entire
mechanism — the record may never reach the broker. Blocking on the future is
what makes `PUBLISHED` mean "the broker has it".

`acks=all` on the producer is the other half: the leader waits for every in-sync
replica before acknowledging, so "the broker has it" survives a leader failure.

### The honest cost

**At-least-once.** If the publisher sends successfully and dies before the
`PUBLISHED` write commits, the lease expires and the event is sent again. That
is unavoidable without a distributed transaction, and it is why the consumer is
idempotent.

### Why polling rather than CDC

Debezium reading the WAL would be more efficient and lower-latency. It is also
another service to run, another failure mode, and a connector configuration to
maintain. At this volume a one-second poll is not measurably worse, and the
operational simplicity is worth more. The seam is clean if that changes: the
publisher is one class.

---

## 4. Transaction boundaries

| Boundary | Propagation | Why |
|---|---|---|
| `NotificationService.create` | `REQUIRED` | Notification + outbox event must be atomic |
| `IdempotencyStore.claim` | **`REQUIRES_NEW`** | The claim must be visible to other transactions *before* the guarded work finishes |
| `IdempotencyStore.recordResponse` | `REQUIRES_NEW` | Must survive even if the business transaction rolls back |
| `DeliveryProcessor.claim` | **`REQUIRES_NEW`** | Commits immediately so a concurrent duplicate sees it |
| `DeliveryProcessor.record` | `REQUIRES_NEW` | Attempt row + final status atomic, independent of the claim |
| `DeadLetterService.record` | `REQUIRES_NEW` | Evidence of a failure must not be rolled back *by* that failure |
| Provider call | **none** | Never hold a connection across someone else's network |

### `@Transactional` is proxy-based

A call from one method of a bean to another method of the **same** bean goes
straight to `this`, bypassing the proxy — the annotation is silently ignored.
Spring cannot warn about it; the code simply does not do what it says.

That is why `IdempotencyStore` is a separate class from `IdempotencyService`,
and why the `REQUIRES_NEW` methods on `DeliveryProcessor` are called through the
bean rather than internally.

### The rollback-only trap

This cost a real bug, and it is worth stating precisely because it looks
harmless:

```java
@Transactional(propagation = REQUIRES_NEW)
public Optional<Record> claim(...) {
    try {
        return Optional.of(repository.saveAndFlush(record));
    } catch (DataIntegrityViolationException duplicate) {
        return Optional.empty();        // ← looks fine. is not.
    }
}
```

The constraint violation marks the transaction **rollback-only**. Catching the
exception does not un-mark it — the mark lives on the transaction, not on the
exception. The method returns normally, Spring's interceptor tries to commit,
and the commit fails with `UnexpectedRollbackException`, which escapes as a 500.

Under concurrency this turned ten of twelve simultaneous requests into server
errors while the unique index was working perfectly. Found by
`IdempotencyConcurrencyIT`; every single-threaded test passed throughout,
because single-threaded there is no race to lose.

**The fix:** let the exception propagate out of the transactional method so the
inner transaction rolls back cleanly, and catch it in the caller — which runs
outside that transaction.

---

## 5. Idempotency and duplicate processing

Two separate problems with two separate mechanisms.

### Client-side: a retried HTTP request

`Idempotency-Key` + a unique index on `(user_id, endpoint, idempotency_key)`.

The index, not an application check, because a "have I seen this key?" read
followed by an insert is a race that two concurrent retries both win. The
pre-read is an optimisation for the common case; the index is the guarantee.

A SHA-256 of the canonical request is stored alongside, so the same key with a
*different* body is a 409 rather than a silent replay. Without it, a client bug
that reused a key would make a genuine second notification disappear.

Stored in PostgreSQL rather than Redis because this is a **correctness**
property: it must be atomic across instances and survive a restart. A Redis
outage would silently start permitting duplicate sends.

### Pipeline-side: a duplicate Kafka event

At-least-once delivery plus outbox republication means duplicates are normal
operation. Three independent defences:

1. **Status check** — `Notification.beginProcessing` refuses unless the status
   is claimable. `PROCESSING` and the terminal states are not, so a second
   consumer cannot re-claim a notification already being delivered.
2. **Optimistic lock** — `@Version` makes a concurrent claim fail rather than
   silently overwrite.
3. **Unique index** — `uq_attempts_notification_number` makes a duplicate
   attempt row impossible even if the first two were somehow passed.

All three are expected to fire occasionally. None is treated as an error; the
consumer acknowledges and moves on.

### What cannot be promised

**Exactly-once processing** is achievable and is what the above delivers.

**Exactly-once delivery to the provider** is not. If the process dies after the
provider accepts the message but before the attempt row commits, the retry sends
it again — the user gets two SMS.

Where a provider supports a client-supplied idempotency key, the adapter passes
`notificationId` and the provider deduplicates. Where it does not, duplicates
remain possible. That is a property of the problem, not of this implementation,
and it is documented rather than papered over.

---

## 6. Retry and dead-letter decisions

### Transient versus permanent

Classification is the **adapter's** job — only it knows what its provider's
errors mean. The processor acts on the answer.

Getting it wrong is expensive both ways: retrying a permanent failure burns the
attempt budget and money; not retrying a transient one throws away a
notification because a provider was rate-limiting for two seconds.

### Exponential backoff with jitter

2s → 4s → 8s, capped, ±20%.

Exponential because the usual cause of a transient failure is an overloaded
provider, and a fixed interval keeps it overloaded. Jitter because without it
every notification that failed during a 30-second outage retries at the same
instant — a thundering herd that can cause a second outage.

The cap is applied **before** jitter. Capping afterwards would let jitter push
the delay above the configured maximum, at which point it is not a maximum.

`jitter-factor: 0` makes delays exactly assertable, which is why jitter is
configuration rather than a hard-coded `Math.random()`.

### Why retries are new events, not redelivery

Throwing from the consumer would make Kafka redeliver — but under the
container's backoff, not the notification's. Two competing retry mechanisms,
with the configured policy silently ignored. One retry policy, expressed once.

The delay is implemented with an in-process scheduled republish. The weakness is
that the schedule lives in one JVM's memory. The database is the backstop: the
row is already `RETRY_SCHEDULED` with a committed `next_attempt_at`, and
`RetrySweeper` recovers anything overdue. A lost schedule costs latency, not the
notification — and that trade-off is the entire reason the sweeper exists.

### Dead letters are a different thing

| | Failed notification | Dead letter |
|---|---|---|
| Meaning | The pipeline worked; the provider said no | The pipeline could not process the message |
| Evidence | Full attempt history | Raw payload + failure reason |
| Implies | A provider is having a bad day | **This service is broken** |
| Alert | Threshold on rate | Any non-zero value |

Conflating them hides real incidents inside ordinary delivery failures, so they
live in separate tables with separate metrics.

Mirrored into a database table as well as the Kafka DLT, because inspecting a
topic needs broker access and a console consumer, while an operator needs to
list, read and replay.

---

## 7. Concurrency and race conditions

| Race | Resolution |
|---|---|
| Two publishers claim the same outbox row | `FOR UPDATE SKIP LOCKED` — each gets a disjoint batch |
| A publisher dies mid-batch | Lease expiry reclaims the rows |
| Two consumers get the same event | Status check + `@Version` + unique attempt index |
| Two requests with the same idempotency key | Unique index; loser replays or is told to retry |
| A retry fires while a delivery is in flight | `PROCESSING` is not claimable |
| Two operators replay the same dead letter | `markReplayed` refuses unless `UNRESOLVED` |

### Why `SKIP LOCKED`

Plain `FOR UPDATE` makes a second publisher **block** on the first's rows,
serialising them into one. `SKIP LOCKED` steps over locked rows and returns the
next unlocked ones, so publishers genuinely run in parallel with no
coordination, leader election or distributed lock.

The row lock only lasts for the claiming transaction, so each row is also
stamped `PUBLISHING` — that stamp carries the claim beyond the lock's lifetime,
and the lease timeout releases it if the publisher dies.

---

## 8. Redis, and its limits

Redis does **one** thing here: rate limiting.

That restraint is deliberate. The temptation is to cache notification lookups or
hold idempotency keys in Redis because it is fast. Both would be wrong:

- **Idempotency in Redis** would make a correctness guarantee depend on a cache
  whose eviction policy (`allkeys-lru`) is tuned for something else entirely. A
  memory-pressure eviction would silently start permitting duplicate sends.
- **Caching notifications** would introduce staleness into exactly the data a
  client is polling for a status change.

The limiter uses `INCR` with the window number in the key. `INCR` on a missing
key creates it at 1 atomically — that single property is what makes it safe
across instances with no lock.

**It fails open**, and that is the most important line in the class. A rate
limiter is a protection mechanism; failing closed would turn a cache outage into
a total outage of notification intake, which is a strictly worse incident.

**The weakness:** a fixed window allows up to 2× the limit across a boundary. A
sliding-window log fixes it and needs a sorted set per caller plus
`ZREMRANGEBYSCORE` on every call. Not worth it here, and stated rather than
implied.

---

## 9. Security

### No registration endpoint

This is internal infrastructure. A service that can send email and SMS to
arbitrary recipients and accepts self-signup is an open relay. Accounts are
provisioned by an administrator; the development account is bootstrapped from
environment configuration and disabled under `prod`.

### Content never leaves

A notification body routinely contains a one-time code or a password-reset link.
So:

- The body is in **no** API response.
- The recipient is **masked** in responses and logs (`p***a@example.com`).
- Neither is in the **Kafka payload** — a topic is replicated and retained for
  days, which is a poor place for secrets. The consumer re-reads the row, which
  is better anyway: a duplicate or stale event cannot make it act on out-of-date
  content.
- `toString()` on the entity, the DTO and the work record all omit them, because
  that is where content leaks into logs by accident.

### 404, not 403

Repository queries take `created_by` in the WHERE clause, so another caller's
notification is indistinguishable from one that does not exist — in the code as
well as in the response. A 403 would confirm the id is real.

### Stateless tokens, and the cost

Verification needs no database round trip, which is the point. The cost is that
disabling an account takes effect at the next expiry (60 minutes). Checking the
database per request would close the window and would also be exactly the round
trip the JWT exists to avoid.

Sixty minutes is longer than a browser-facing API would use, because the callers
are services holding a token for a batch of work, not humans clicking around.
There is no refresh token: a service can re-authenticate whenever it likes, so
it would be a second credential to store and revoke for nothing.

---

## 10. Failure scenarios and recovery

| Failure | What happens | Recovery |
|---|---|---|
| **Kafka down at submit** | Notification + outbox event commit; publish fails | Publisher retries each poll; backlog gauge rises |
| **Kafka down for hours** | Outbox grows | Drains automatically; `max-publish-attempts` then parks events as `FAILED` for an operator |
| **Publisher dies mid-batch** | Rows stuck `PUBLISHING` | Lease expires, next publisher reclaims. **[Verified](TEST-REPORT.md)** |
| **App dies between save and publish** | Both committed, nothing published | Publisher picks it up on restart. **[Verified](TEST-REPORT.md)** |
| **Consumer dies mid-delivery** | Notification stuck `PROCESSING` | **Not automatic** — `notification.stuck.processing` gauge surfaces it. Deliberate: a message may have reached the provider, so auto-retry risks a duplicate send |
| **Provider down** | Transient failures | Exponential backoff, then `FAILED` with full history |
| **Redis down** | Rate limiter fails open | Warning logged; `notification.ratelimit.failopen` counter |
| **Database down** | API returns 503 | Nothing accepted, nothing lost |
| **Malformed event** | Dead-lettered immediately | Operator inspects and replays or discards |
| **Instance dies during a retry backoff** | Scheduled republish lost | `RetrySweeper` recovers it from `next_attempt_at` |

The one deliberately manual case is a consumer dying mid-delivery. Everything
else recovers on its own.

---

## 11. Scalability bottlenecks

In the order they would actually bite:

### 1. Outbox publisher throughput

A single poll claims `batch-size` rows and publishes them **serially**,
blocking on each acknowledgement. At ~5ms per ack that is roughly 200
events/second per instance.

*Fix:* publish a batch concurrently and await all futures; or run more
instances, which `SKIP LOCKED` already supports with no coordination.

### 2. Kafka partition count

Consumer concurrency is capped by partitions — three here, so at most three
consumer threads do useful work however many instances run.

*Fix:* more partitions. Note this can only be increased, never decreased, and
increasing it changes key-to-partition mapping, which breaks per-key ordering
for keys already in flight.

### 3. Provider latency

The real ceiling. At 50ms per call and 3 partitions, ~60 notifications/second.

*Fix:* this is where the architecture would genuinely change — a bounded
executor inside the consumer to parallelise provider calls within a partition,
accepting that ordering within a key is then lost.

### 4. The `notifications` table

Unbounded growth. Every query is indexed and bounded, so reads stay fast, but
the table and its indexes grow forever.

*Fix:* partition by `created_at` and detach old partitions, or archive terminal
notifications older than the retention period.

### 5. Single database

All writes go to one PostgreSQL instance.

*Fix:* read replicas first — the `readOnly = true` annotations are already in
place to route on. Sharding by tenant would be the next step and is a long way
past where this would need to be.

### What would change first at real scale

1. Concurrent publishing within a batch (biggest win, smallest change).
2. More partitions and more consumer instances.
3. Partition the `notifications` table by month.
4. Per-tenant or per-priority topics, so one noisy caller cannot delay another.
