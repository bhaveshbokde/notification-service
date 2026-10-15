package com.bhavesh.notification.common.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Time-ordered UUID generation.
 *
 * <h2>Why not {@link UUID#randomUUID()}</h2>
 * A version-4 UUID is 122 random bits. As a primary key that is close to the
 * worst possible choice for an index: every insert lands at a random point in
 * the B-tree, so the pages being written are scattered across the whole index
 * rather than concentrated at one end. Once the index outgrows the buffer pool
 * each insert becomes a random read followed by a random write, and insert
 * throughput falls off a cliff that gets steeper as the table grows.
 *
 * <p>This generates the layout standardised as UUID version 7: a 48-bit
 * millisecond timestamp in the high bits, then 74 bits of randomness. Keys
 * generated close together in time sort close together, so inserts append to
 * the right-hand edge of the index and the hot pages stay hot.
 *
 * <pre>
 *   0               1               2               3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  ┌───────────────────────────────────────────────────────────────┐
 *  │                   unix_ts_ms (48 bits)                        │
 *  ├───────────────┬───────────────────────────────────────────────┤
 *  │  ver (0b0111) │            rand_a (12 bits)                   │
 *  ├───┬───────────┴───────────────────────────────────────────────┤
 *  │var│                    rand_b (62 bits)                       │
 *  └───┴───────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Why the id is generated in Java rather than by the database</h2>
 * The notification's id is needed <em>before</em> the insert, so the outbox
 * event written in the same transaction can reference it. A database-generated
 * key would require either reading it back — which rules out batching — or a
 * second update pass. Generating it here keeps the whole write to one
 * statement per table.
 *
 * <p>The implementation is also why the id is safe to publish: it carries a
 * creation timestamp and nothing else. It does not reveal how many
 * notifications the service has handled, and a neighbouring value cannot be
 * guessed, which a sequential key would permit on both counts.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /** A time-ordered UUID using the system clock. */
    public static UUID timeOrdered() {
        return timeOrdered(System.currentTimeMillis());
    }

    /** Clock-injecting variant, so tests can assert on ordering without sleeping. */
    public static UUID timeOrdered(Clock clock) {
        return timeOrdered(clock.millis());
    }

    /**
     * Derives the id from a supplied instant.
     *
     * <p>Used wherever an entity already has an authoritative creation time, so
     * the timestamp embedded in the id and the entity's own {@code createdAt}
     * cannot drift apart.
     */
    public static UUID timeOrdered(java.time.Instant instant) {
        return timeOrdered(instant.toEpochMilli());
    }

    static UUID timeOrdered(long epochMilli) {
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        // High 64 bits: 48-bit timestamp, 4-bit version (7), 12 random bits.
        long most = (epochMilli & 0xFFFF_FFFF_FFFFL) << 16;
        most |= 0x7000L;                                   // version 7
        most |= ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        // Low 64 bits: 2-bit IETF variant (0b10), 62 random bits.
        long least = 0;
        for (int i = 2; i < 10; i++) {
            least = (least << 8) | (randomBytes[i] & 0xFFL);
        }
        least &= 0x3FFF_FFFF_FFFF_FFFFL;                   // clear the top two bits
        least |= 0x8000_0000_0000_0000L;                   // variant 0b10

        return new UUID(most, least);
    }

    /**
     * The millisecond timestamp embedded in a time-ordered UUID.
     *
     * <p>Useful for diagnostics — "when was this notification created?" without
     * a database round trip — and for asserting ordering in tests.
     *
     * @throws IllegalArgumentException if the UUID is not version 7
     */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException(
                    "Not a time-ordered (v7) UUID: version " + uuid.version());
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    /**
     * A short correlation id for logs and error bodies.
     *
     * <p>Sixteen hex characters: long enough that a collision inside one log
     * retention window is not a practical concern, short enough to read over
     * the phone.
     */
    public static String traceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
