package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotOutboxEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The transport RT5 promised: B's strategy-bot commands travel from the backend's transactional
 * outbox to {@link StrategyBotControlConsumer} by this poller reading the shared canonical database.
 *
 * <p>The delivery bookkeeping is {@code operations.outbox_consumer_receipts}, one row per (handler,
 * message), which is what canonical provides for exactly this: an at-least-once consumer that has to
 * recognise a message it already handled. The poller deliberately does <em>not</em> touch
 * {@code outbox_messages.delivery_status} — that column is the publisher's state, and a consumer
 * marking a message PUBLISHED would be claiming something about every other consumer too.
 *
 * <p>Delivery is at-least-once on purpose. A crash after consume but before the receipt is committed
 * leaves the receipt {@code PROCESSING} until its lease expires, when the next cycle reclaims and
 * redelivers; the consumer's checkpoint — itself derived from these receipts — is what makes the
 * second delivery a no-op. A failed consume becomes {@code RETRYABLE_FAILURE} with a backoff, and at
 * {@code maxAttempts} it becomes {@code PERMANENT_FAILURE} for the operator rather than retrying
 * forever behind the rest of the domain.
 */
public final class StrategyBotOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(StrategyBotOutboxPoller.class);

    /** The two command types B publishes on the strategy-bot domain for this consumer. */
    private static final String COMMAND_TYPES = "('BOT_RUN_COMMAND', 'BOT_STOP_COMMAND')";

    /**
     * Claims the due strategy-bot commands this handler has not completed.
     *
     * <p>The insert is the claim: the receipt's primary key is (handler, message), so two workers
     * racing the same message resolve to one row, and {@code do update} only takes the claim when the
     * existing row is retryable or its lease has lapsed. A {@code COMPLETED} or
     * {@code PERMANENT_FAILURE} receipt matches no branch and the message is skipped.
     */
    private static final String CLAIM = """
            insert into operations.outbox_consumer_receipts (
                consumer_handler_id, outbox_message_id, producer_idempotency_key, payload_hash,
                status, claim_token, claimed_by, claimed_at, claim_expires_at,
                receive_attempt_count, first_received_at, last_received_at)
            select :handlerId, message.id, message.producer_idempotency_key, message.payload_hash,
                   'PROCESSING', gen_random_uuid(), :claimedBy, :now, :leaseExpiresAt,
                   1, :now, :now
            from operations.outbox_messages message
            left join operations.outbox_consumer_receipts existing
                   on existing.consumer_handler_id = :handlerId
                  and existing.outbox_message_id = message.id
            where message.owner_domain = 'strategy-bot'
              and message.event_type in %s
              and (existing.status is null
                   or (existing.status = 'RETRYABLE_FAILURE' and existing.last_received_at <= :dueBefore)
                   or (existing.status = 'PROCESSING' and existing.claim_expires_at <= :now))
            order by message.created_at, message.aggregate_sequence, message.id
            limit :batchSize
            on conflict (consumer_handler_id, outbox_message_id) do update set
                status = 'PROCESSING',
                claim_token = gen_random_uuid(),
                claimed_by = excluded.claimed_by,
                claimed_at = excluded.claimed_at,
                claim_expires_at = excluded.claim_expires_at,
                receive_attempt_count = operations.outbox_consumer_receipts.receive_attempt_count + 1,
                last_received_at = excluded.last_received_at,
                failure_code = null
            returning outbox_message_id, claim_token, receive_attempt_count
            """.formatted(COMMAND_TYPES);

    private static final String ENVELOPE = """
            select id, aggregate_id, aggregate_sequence, event_type, event_schema_version,
                   idempotency_key, payload_document::text as payload_document, created_at
            from operations.outbox_messages
            where id = :id
            """;

    private static final String COMPLETE = """
            update operations.outbox_consumer_receipts set
                status = 'COMPLETED',
                completed_at = :now,
                claim_token = null, claimed_by = null, claimed_at = null, claim_expires_at = null,
                failure_code = null
            where consumer_handler_id = :handlerId and outbox_message_id = :id
              and claim_token = :claimToken
            """;

    private static final String FAIL = """
            update operations.outbox_consumer_receipts set
                status = cast(:status as operations.consumer_receipt_status),
                failure_code = :failureCode,
                claim_token = null, claimed_by = null, claimed_at = null, claim_expires_at = null
            where consumer_handler_id = :handlerId and outbox_message_id = :id
              and claim_token = :claimToken
            """;

    private final JdbcClient jdbc;
    private final StrategyBotControlConsumer consumer;
    private final Clock clock;
    private final String handlerId;
    private final String workerId;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryBackoff;
    private final int maxAttempts;

    public StrategyBotOutboxPoller(
            JdbcClient jdbc,
            StrategyBotControlConsumer consumer,
            Clock clock,
            String handlerId,
            String workerId,
            int batchSize,
            Duration lease,
            Duration retryBackoff,
            int maxAttempts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.handlerId = requireText(handlerId, "handlerId");
        this.workerId = requireText(workerId, "workerId");
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.lease = requirePositiveDuration(lease, "lease");
        this.retryBackoff = requirePositiveDuration(retryBackoff, "retryBackoff");
        this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");
    }

    /** One polling cycle: claim due strategy-bot commands, deliver each, record the outcome. */
    public int pollOnce() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Claim> claims = jdbc.sql(CLAIM)
                .param("handlerId", handlerId)
                .param("claimedBy", workerId)
                .param("now", now)
                .param("leaseExpiresAt", now.plus(lease))
                .param("dueBefore", now.minus(retryBackoff))
                .param("batchSize", batchSize)
                .query((rs, row) -> new Claim(
                        rs.getObject("outbox_message_id", UUID.class),
                        rs.getObject("claim_token", UUID.class),
                        rs.getInt("receive_attempt_count")))
                .list();

        // `INSERT ... RETURNING` reports rows in the order the insert produced them, not the order of
        // the select's ORDER BY, so publication order is re-established from the messages themselves.
        // It is the order B produced the commands in: a run created before a stop has to reach the
        // consumer first, or the stop is applied to a bot that never started.
        List<Delivery> deliveries = claims.stream()
                .map(claim -> new Delivery(claim, envelopeOf(claim.messageId())))
                .sorted(java.util.Comparator
                        .comparing((Delivery delivery) -> delivery.envelope().createdAt())
                        .thenComparing(delivery -> delivery.envelope().aggregateSequence())
                        .thenComparing(delivery -> delivery.envelope().messageId()))
                .toList();

        int delivered = 0;
        for (Delivery delivery : deliveries) {
            EnvelopeRow row = delivery.envelope();
            try {
                consumer.consume(new StrategyBotOutboxEnvelope(
                        row.messageId(), "strategy-bot", row.aggregateId(), row.aggregateSequence(),
                        row.eventType(), row.eventSchemaVersion(), row.idempotencyKey(),
                        row.payloadDocument()));
                jdbc.sql(COMPLETE)
                        .param("now", now)
                        .param("handlerId", handlerId)
                        .param("id", row.messageId())
                        .param("claimToken", delivery.claim().claimToken())
                        .update();
                delivered++;
            } catch (RuntimeException failure) {
                recordFailure(delivery, failure);
            }
        }
        return delivered;
    }

    private EnvelopeRow envelopeOf(UUID messageId) {
        return jdbc.sql(ENVELOPE)
                .param("id", messageId)
                .query((rs, row) -> new EnvelopeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getLong("aggregate_sequence"),
                        rs.getString("event_type"),
                        rs.getString("event_schema_version"),
                        rs.getString("idempotency_key"),
                        rs.getString("payload_document"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    private void recordFailure(Delivery delivery, RuntimeException failure) {
        boolean exhausted = delivery.claim().receiveAttemptCount() >= maxAttempts;
        UUID messageId = delivery.envelope().messageId();
        if (exhausted) {
            log.error("strategy-bot command {} failed permanently after {} attempts",
                    messageId, delivery.claim().receiveAttemptCount(), failure);
        } else {
            log.warn("strategy-bot command {} failed on attempt {}; retrying after {}",
                    messageId, delivery.claim().receiveAttemptCount(), retryBackoff, failure);
        }
        jdbc.sql(FAIL)
                .param("status", exhausted ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE")
                .param("failureCode", boundedReason(failure))
                .param("handlerId", handlerId)
                .param("id", messageId)
                .param("claimToken", delivery.claim().claimToken())
                .update();
    }

    private static String boundedReason(RuntimeException failure) {
        String reason = failure.getClass().getSimpleName();
        return reason.substring(0, Math.min(reason.length(), 80));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record Claim(UUID messageId, UUID claimToken, int receiveAttemptCount) {}

    private record Delivery(Claim claim, EnvelopeRow envelope) {}

    private record EnvelopeRow(
            UUID messageId, UUID aggregateId, long aggregateSequence, String eventType,
            String eventSchemaVersion, String idempotencyKey, String payloadDocument,
            OffsetDateTime createdAt) {}
}
