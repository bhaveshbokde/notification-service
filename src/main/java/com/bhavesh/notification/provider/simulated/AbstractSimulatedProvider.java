package com.bhavesh.notification.provider.simulated;

import com.bhavesh.notification.config.ProviderProperties;
import com.bhavesh.notification.provider.NotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Shared behaviour for the simulated EMAIL, SMS and IN_APP adapters.
 *
 * <p><strong>NOTHING HERE SENDS ANYTHING.</strong> No SMTP connection, no HTTP
 * call, no gateway. These adapters exist so the whole pipeline — outbox, Kafka,
 * retries, dead-lettering, attempt history — can be run and demonstrated on a
 * laptop with no paid accounts, no SMTP credentials and no real phone numbers.
 *
 * <p>Every place a reader might form the wrong impression says so explicitly:
 * the class name, the log line, the {@code simulated} flag in the API response
 * and the README. Reporting a simulated send as a real one is a mistake that is
 * very easy to make by accident and very damaging when someone relies on it.
 *
 * <h2>Scripted outcomes, not random ones</h2>
 * The outcome is a deterministic function of the recipient, using magic
 * prefixes (see {@link SimulatedOutcome}). A random failure rate would make the
 * test suite flaky and a demonstration unreproducible, and "it only fails
 * sometimes" is the worst possible property for a delivery path.
 *
 * <p>{@code failureRate} exists for the separate job of watching retry
 * behaviour under sustained load, and is 0.0 by default.
 *
 * <h2>How a real provider would slot in</h2>
 * Implement {@link NotificationProvider} — for example {@code SesEmailProvider}
 * — annotate it {@code @Primary} or gate both on a property, and change
 * nothing else. The processor, the retry policy and the attempt history are all
 * on this side of the interface. The one thing a real adapter should do that
 * these cannot is pass {@code notificationId} as the provider's own idempotency
 * key, which is the only genuine defence against a duplicate send.
 */
public abstract class AbstractSimulatedProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractSimulatedProvider.class);

    private final SecureRandom random = new SecureRandom();
    protected final ProviderProperties properties;

    protected AbstractSimulatedProvider(ProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public final boolean isSimulated() {
        return true;
    }

    @Override
    public final DeliveryResult send(DeliveryRequest request) {
        applySimulatedLatency();

        SimulatedOutcome scripted = SimulatedOutcome.forRecipient(request.recipient());
        DeliveryResult result = switch (scripted) {
            case SUCCESS -> DeliveryResult.success(reference());
            case TRANSIENT -> DeliveryResult.transientFailure(
                    "Simulated transient failure: provider temporarily unavailable");
            case PERMANENT -> DeliveryResult.permanentFailure(
                    "Simulated permanent failure: recipient rejected by provider");
            case FLAKY -> flakyOutcome(request);
            case DEFAULT -> spontaneousFailure()
                    ? DeliveryResult.transientFailure(
                            "Simulated transient failure (configured failure-rate)")
                    : DeliveryResult.success(reference());
        };

        // Logs the channel, a masked recipient and the outcome. Never the
        // subject or the body — those routinely contain one-time codes and
        // password-reset links.
        log.info("SIMULATED {} delivery (no real message sent): recipient={} attempt={} outcome={}",
                channel(), request.maskedRecipient(), request.attemptNumber(), result.outcome());

        return result;
    }

    /**
     * Fails on the first attempt, then succeeds.
     *
     * <p>This is the fixture that makes the retry path demonstrable end to end:
     * a caller can submit one notification and watch it go
     * PENDING → PROCESSING → RETRY_SCHEDULED → PROCESSING → SENT with two rows
     * in the attempt history. Deterministic, so a test can assert on it.
     */
    private DeliveryResult flakyOutcome(DeliveryRequest request) {
        if (request.attemptNumber() <= 1) {
            return DeliveryResult.transientFailure(
                    "Simulated transient failure on attempt 1 (flaky recipient)");
        }
        return DeliveryResult.success(reference());
    }

    private boolean spontaneousFailure() {
        double rate = properties.failureRate();
        return rate > 0.0 && random.nextDouble() < rate;
    }

    /** A synthetic provider reference, shaped like a real one. */
    private String reference() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return "SIM-%s-%s".formatted(
                channel().name(),
                HexFormat.of().withUpperCase().formatHex(bytes));
    }

    /**
     * Artificial delay, so the asynchronous pipeline is observable rather than
     * completing before anyone can watch it.
     *
     * <p>Restores the interrupt flag before returning. Swallowing an
     * {@link InterruptedException} without re-interrupting leaves the thread
     * looking un-interrupted to everything upstream, so a shutdown silently
     * fails to propagate.
     */
    private void applySimulatedLatency() {
        long millis = properties.simulatedLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Simulated latency interrupted; answering immediately");
        }
    }

    /**
     * How a recipient scripts its own delivery outcome.
     *
     * <p>Magic prefixes rather than a separate request field, so the behaviour
     * can be exercised through the ordinary API with no test-only parameter
     * that might accidentally be honoured in production.
     */
    public enum SimulatedOutcome {

        /** {@code success+...} — delivers. */
        SUCCESS,

        /** {@code transient+...} — fails retryably every time, so retries exhaust. */
        TRANSIENT,

        /** {@code permanent+...} — fails permanently, so no retry is attempted. */
        PERMANENT,

        /** {@code flaky+...} — fails once, then succeeds. Demonstrates recovery. */
        FLAKY,

        /** Anything else — succeeds, unless {@code failure-rate} says otherwise. */
        DEFAULT;

        public static SimulatedOutcome forRecipient(String recipient) {
            if (recipient == null) {
                return DEFAULT;
            }
            String value = recipient.trim().toLowerCase(Locale.ROOT);
            // The local part of an address, or the start of a phone/user id.
            if (value.startsWith("transient") || value.contains("+transient")) {
                return TRANSIENT;
            }
            if (value.startsWith("permanent") || value.contains("+permanent")) {
                return PERMANENT;
            }
            if (value.startsWith("flaky") || value.contains("+flaky")) {
                return FLAKY;
            }
            if (value.startsWith("success") || value.contains("+success")) {
                return SUCCESS;
            }
            return DEFAULT;
        }
    }
}
