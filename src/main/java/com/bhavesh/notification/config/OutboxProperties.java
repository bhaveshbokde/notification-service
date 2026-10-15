package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code notification.outbox.*}.
 *
 * @param pollInterval       how often the publisher looks for work. One second
 *                           is the floor on added latency between accepting a
 *                           notification and publishing its event. Lower means
 *                           more empty queries against the database; higher
 *                           means a visibly slower pipeline. One second is the
 *                           compromise, and it is configurable because the
 *                           right answer depends on volume.
 *
 * @param batchSize          rows claimed per poll. Larger batches amortise the
 *                           round trip but hold their claim longer, so a
 *                           publisher crash strands more rows until the lease
 *                           expires.
 *
 * @param leaseDuration      how long a claim is honoured before another
 *                           publisher may steal it. <strong>Must exceed the
 *                           worst-case time to publish one batch.</strong> Set
 *                           it too low and a merely slow publisher has its rows
 *                           taken while it is still sending them, which
 *                           publishes the event twice.
 *
 * @param maxPublishAttempts after this many failures an event is marked FAILED
 *                           and left for an operator. Retrying forever would
 *                           turn one poisoned event — an oversized payload, a
 *                           topic that cannot be created — into an infinite
 *                           loop that starves every healthy event behind it.
 */
@ConfigurationProperties(prefix = "notification.outbox")
public record OutboxProperties(
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT2M") Duration leaseDuration,
        @DefaultValue("5") int maxPublishAttempts
) {
}
