package com.idea2strategy.trading.worker.roomevaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F's root #181 handoff: consume E's account-open request and own every official ledger write.
 *
 * <p>The claim lease is committed before the effect. The completed receipt, bot event, accounts,
 * balanced posting and completion outbox then share one transaction. A crash anywhere in that
 * second transaction therefore leaves only an expiring claim and no partial business state.
 */
public final class RoomEvaluationAccountOpenPoller {

    public static final String HANDLER_ID = "trading-worker.room-evaluation-account-open.v1";
    public static final String REQUEST_TYPE = "ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED";
    public static final String REQUEST_SCHEMA = "room-evaluation-account-open-requested.v1";
    public static final String OPENED_TYPE = "ROOM_EVALUATION_ACCOUNT_OPENED";
    public static final String OPENED_SCHEMA = "room-evaluation-account-opened.v1";
    public static final String REJECTED_TYPE = "ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED";
    public static final String REJECTED_SCHEMA = "room-evaluation-account-open-rejected.v1";

    private static final UUID SYSTEM_ACTOR = UUID.fromString("f1810000-0000-4000-8000-000000000001");
    private static final Pattern MONEY = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8})?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private static final String CLAIM = """
            insert into operations.outbox_consumer_receipts (
                consumer_handler_id, outbox_message_id, producer_idempotency_key, payload_hash,
                status, claim_token, claimed_by, claimed_at, claim_expires_at,
                receive_attempt_count, first_received_at, last_received_at)
            select :handler, message.id, message.producer_idempotency_key, message.payload_hash,
                   'PROCESSING', gen_random_uuid(), :worker, :now, :expires,
                   1, :now, :now
            from operations.outbox_messages message
            left join operations.outbox_consumer_receipts receipt
              on receipt.consumer_handler_id = :handler and receipt.outbox_message_id = message.id
            where message.owner_domain = 'room-performance'
              and message.event_type = :eventType
              and message.event_schema_version = :schemaVersion
              and (receipt.status is null
                or (receipt.status = 'RETRYABLE_FAILURE' and receipt.last_received_at <= :dueBefore)
                or (receipt.status = 'PROCESSING' and receipt.claim_expires_at <= :now))
            order by message.created_at, message.id
            limit 1
            on conflict (consumer_handler_id, outbox_message_id) do update set
                status = 'PROCESSING', claim_token = gen_random_uuid(), claimed_by = excluded.claimed_by,
                claimed_at = excluded.claimed_at, claim_expires_at = excluded.claim_expires_at,
                receive_attempt_count = operations.outbox_consumer_receipts.receive_attempt_count + 1,
                last_received_at = excluded.last_received_at, failure_code = null
            where (operations.outbox_consumer_receipts.status = 'RETRYABLE_FAILURE'
                    and operations.outbox_consumer_receipts.last_received_at <= :dueBefore)
               or (operations.outbox_consumer_receipts.status = 'PROCESSING'
                    and operations.outbox_consumer_receipts.claim_expires_at <= :now)
            returning outbox_message_id, claim_token, receive_attempt_count
            """;

    private final JdbcClient jdbc;
    private final BotEventStore events;
    private final LedgerStore ledger;
    private final ObjectMapper json;
    private final Clock clock;
    private final String workerId;
    private final Duration lease;
    private final Duration retryBackoff;
    private final int maxAttempts;
    private final TransactionTemplate transaction;

    public RoomEvaluationAccountOpenPoller(
            JdbcClient jdbc,
            BotEventStore events,
            LedgerStore ledger,
            ObjectMapper json,
            Clock clock,
            String workerId,
            Duration lease,
            Duration retryBackoff,
            int maxAttempts,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = requireText(workerId, "workerId");
        this.lease = requirePositive(lease, "lease");
        this.retryBackoff = requirePositive(retryBackoff, "retryBackoff");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Processes up to {@code batchSize} requests, returning the number completed successfully. */
    public int pollOnce(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        int completed = 0;
        for (int index = 0; index < batchSize; index++) {
            Claim claim = claimNext();
            if (claim == null) break;
            try {
                transaction.executeWithoutResult(status -> complete(claim));
                completed++;
            } catch (PermanentFailure permanent) {
                recordFailure(claim, permanent.reasonCode, true);
            } catch (RuntimeException transientFailure) {
                recordFailure(claim, transientFailure.getClass().getSimpleName(),
                        claim.attempt() >= maxAttempts);
            }
        }
        return completed;
    }

    private Claim claimNext() {
        OffsetDateTime now = offset(clock.instant());
        return jdbc.sql(CLAIM)
                .param("handler", HANDLER_ID)
                .param("worker", workerId)
                .param("now", now)
                .param("expires", now.plus(lease))
                .param("dueBefore", now.minus(retryBackoff))
                .param("eventType", REQUEST_TYPE)
                .param("schemaVersion", REQUEST_SCHEMA)
                .query((rs, row) -> new Claim(
                        rs.getObject("outbox_message_id", UUID.class),
                        rs.getObject("claim_token", UUID.class),
                        rs.getInt("receive_attempt_count")))
                .optional()
                .orElse(null);
    }

    private void complete(Claim claim) {
        Envelope envelope = loadEnvelope(claim.messageId());
        verifyClaim(claim, envelope);
        Request request = decode(envelope);
        rejectProducerKeyConflict(envelope);

        ObjectNode summary = json.createObjectNode()
                .put("contractVersion", REQUEST_SCHEMA)
                .put("commandId", request.commandId().toString())
                .put("roomId", request.roomId().toString())
                .put("participationId", request.participationId().toString())
                .put("evaluationSegmentId", request.evaluationSegmentId().toString())
                .put("initialCash", request.initialCashText())
                .put("currency", request.currency())
                .put("feePolicyVersionId", request.feePolicyVersionId().toString())
                .put("buyingPowerPolicyVersionId", request.buyingPowerPolicyVersionId().toString())
                .put("requestPayloadHash", envelope.payloadHash());
        BotEvent event = events.appendOrLoad(BotEventAppend.of(
                request.botId(), BotEventType.INITIAL_CAPITAL_POSTED,
                request.participationId().toString(), request.commandId(), request.effectiveAt(),
                write(summary)));

        LedgerTransaction posting = LedgerTransaction.standard(event.eventId(), request.effectiveAt(), List.of(
                LedgerEntryDraft.debit("CASH", request.currency(), request.initialCash()),
                LedgerEntryDraft.credit("CAPITAL", request.currency(), request.initialCash())));
        ledger.append(PostLedgerTransactionCommand.botWide(request.botId(), posting));

        UUID cashAccountId = accountId(request.botId(), "CASH", request.currency());
        UUID capitalAccountId = accountId(request.botId(), "CAPITAL", request.currency());
        appendOpened(envelope, request, event, posting, cashAccountId, capitalAccountId);

        int updated = jdbc.sql("""
                        update operations.outbox_consumer_receipts set status = 'COMPLETED',
                            completed_at = :now, result_hash = :resultHash,
                            claim_token = null, claimed_by = null,
                            claimed_at = null, claim_expires_at = null, failure_code = null
                        where consumer_handler_id = :handler and outbox_message_id = :messageId
                          and claim_token = :token and payload_hash = :payloadHash
                        """)
                .param("now", offset(clock.instant()))
                .param("resultHash", envelope.payloadHash())
                .param("handler", HANDLER_ID)
                .param("messageId", envelope.messageId())
                .param("token", claim.token())
                .param("payloadHash", envelope.payloadHash())
                .update();
        if (updated != 1) throw new IllegalStateException("room account receipt lease is stale");
    }

    private Envelope loadEnvelope(UUID messageId) {
        return jdbc.sql("""
                        select id, aggregate_id, event_type, event_schema_version,
                               producer_idempotency_key, payload_hash, payload_document::text
                        from operations.outbox_messages where id = :id
                        """)
                .param("id", messageId)
                .query((rs, row) -> new Envelope(
                        rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), rs.getString("event_schema_version"),
                        rs.getString("producer_idempotency_key"), rs.getString("payload_hash"),
                        rs.getString("payload_document")))
                .single();
    }

    private void verifyClaim(Claim claim, Envelope envelope) {
        Boolean valid = jdbc.sql("""
                        select claim_token = :token and status = 'PROCESSING'
                               and payload_hash = :payloadHash
                        from operations.outbox_consumer_receipts
                        where consumer_handler_id = :handler and outbox_message_id = :messageId
                        for update
                        """)
                .param("handler", HANDLER_ID).param("messageId", claim.messageId())
                .param("token", claim.token()).param("payloadHash", envelope.payloadHash())
                .query(Boolean.class).optional().orElse(false);
        if (!valid) throw new IllegalStateException("room account receipt lease is stale");
    }

    private Request decode(Envelope envelope) {
        try {
            if (!REQUEST_TYPE.equals(envelope.eventType()) || !REQUEST_SCHEMA.equals(envelope.schemaVersion())) {
                throw permanent("UNSUPPORTED_CONTRACT");
            }
            JsonNode root = json.readTree(envelope.payloadDocument());
            Request request = new Request(
                    uuid(root, "commandId"), uuid(root, "messageId"), text(root, "producerIdempotencyKey"),
                    uuid(root, "roomId"), uuid(root, "participationId"), uuid(root, "botId"),
                    uuid(root, "evaluationSegmentId"), text(root, "initialCash"), text(root, "currency"),
                    uuid(root, "feePolicyVersionId"), uuid(root, "buyingPowerPolicyVersionId"),
                    Instant.parse(text(root, "effectiveAt")));
            if (!request.messageId().equals(envelope.messageId())
                    || !request.participationId().equals(envelope.aggregateId())
                    || !request.producerIdempotencyKey().equals(envelope.producerKey())) {
                throw permanent("ENVELOPE_MISMATCH");
            }
            return request;
        } catch (PermanentFailure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw permanent("INVALID_REQUEST");
        } catch (Exception invalidJson) {
            throw permanent("INVALID_REQUEST");
        }
    }

    private void rejectProducerKeyConflict(Envelope envelope) {
        Integer conflicts = jdbc.sql("""
                        select count(*) from operations.outbox_consumer_receipts
                        where consumer_handler_id = :handler
                          and producer_idempotency_key = :producerKey
                          and outbox_message_id <> :messageId
                          and payload_hash <> :payloadHash
                        """)
                .param("handler", HANDLER_ID).param("producerKey", envelope.producerKey())
                .param("messageId", envelope.messageId()).param("payloadHash", envelope.payloadHash())
                .query(Integer.class).single();
        if (conflicts > 0) throw permanent("PRODUCER_KEY_CONTENT_CONFLICT");
    }

    private void appendOpened(Envelope envelope, Request request, BotEvent event,
            LedgerTransaction posting, UUID cashAccountId, UUID capitalAccountId) {
        UUID messageId = stableId("opened:" + envelope.messageId());
        ObjectNode payload = json.createObjectNode()
                .put("requestMessageId", envelope.messageId().toString())
                .put("commandId", request.commandId().toString())
                .put("producerIdempotencyKey", envelope.producerKey())
                .put("requestPayloadHash", envelope.payloadHash())
                .put("roomId", request.roomId().toString())
                .put("participationId", request.participationId().toString())
                .put("botId", request.botId().toString())
                .put("evaluationSegmentId", request.evaluationSegmentId().toString())
                .put("botEventId", event.eventId().toString())
                .put("botEventSequence", event.eventSequence())
                .put("ledgerTransactionId", posting.transactionId().toString())
                .put("cashAccountId", cashAccountId.toString())
                .put("capitalAccountId", capitalAccountId.toString())
                .put("initialCash", request.initialCashText())
                .put("currency", request.currency())
                .put("feePolicyVersionId", request.feePolicyVersionId().toString())
                .put("buyingPowerPolicyVersionId", request.buyingPowerPolicyVersionId().toString())
                .put("completedAt", clock.instant().toString());
        jdbc.sql("""
                        insert into operations.outbox_messages (
                            id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                            event_schema_version, payload_document, idempotency_key,
                            producer_idempotency_key, created_at)
                        values (:id, 'trading', :aggregateId, 1, :eventType, :schemaVersion,
                            cast(:payload as jsonb), :idempotencyKey, :producerKey, :createdAt)
                        on conflict (idempotency_key) do nothing
                        """)
                .param("id", messageId).param("aggregateId", request.participationId())
                .param("eventType", OPENED_TYPE).param("schemaVersion", OPENED_SCHEMA)
                .param("payload", write(payload)).param("idempotencyKey", "ROOM_ACCOUNT_OPENED:" + envelope.messageId())
                .param("producerKey", envelope.producerKey()).param("createdAt", offset(clock.instant()))
                .update();
    }

    private UUID accountId(UUID botId, String accountType, String currency) {
        return jdbc.sql("""
                        select id from trading.ledger_accounts
                        where bot_id = :botId and account_key = :accountKey
                        """)
                .param("botId", botId).param("accountKey", "BOT:" + accountType + ":" + currency)
                .query(UUID.class).single();
    }

    private void recordFailure(Claim claim, String reason, boolean permanent) {
        transaction.executeWithoutResult(status -> {
            Envelope envelope = loadEnvelope(claim.messageId());
            String bounded = reason.substring(0, Math.min(reason.length(), 80));
            jdbc.sql("""
                            update operations.outbox_consumer_receipts set
                                status = cast(:status as operations.consumer_receipt_status),
                                failure_code = :reason, claim_token = null, claimed_by = null,
                                claimed_at = null, claim_expires_at = null
                            where consumer_handler_id = :handler and outbox_message_id = :messageId
                              and claim_token = :token
                            """)
                    .param("status", permanent ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE")
                    .param("reason", bounded).param("handler", HANDLER_ID)
                    .param("messageId", claim.messageId()).param("token", claim.token()).update();
            if (permanent) auditConflict(envelope, bounded);
            if (permanent) appendRejected(envelope, bounded);
        });
    }

    private void appendRejected(Envelope envelope, String reason) {
        Request request;
        try {
            request = decode(envelope);
        } catch (PermanentFailure invalid) {
            return;
        }
        ObjectNode payload = json.createObjectNode()
                .put("requestMessageId", envelope.messageId().toString())
                .put("commandId", request.commandId().toString())
                .put("producerIdempotencyKey", envelope.producerKey())
                .put("requestPayloadHash", envelope.payloadHash())
                .put("roomId", request.roomId().toString())
                .put("participationId", request.participationId().toString())
                .put("botId", request.botId().toString())
                .put("evaluationSegmentId", request.evaluationSegmentId().toString())
                .put("initialCash", request.initialCashText())
                .put("currency", request.currency())
                .put("feePolicyVersionId", request.feePolicyVersionId().toString())
                .put("buyingPowerPolicyVersionId", request.buyingPowerPolicyVersionId().toString())
                .put("reasonCode", reason)
                .put("rejectedAt", clock.instant().toString());
        jdbc.sql("""
                        insert into operations.outbox_messages (
                            id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                            event_schema_version, payload_document, idempotency_key,
                            producer_idempotency_key, created_at)
                        values (:id, 'trading', :aggregateId, 1, :eventType, :schemaVersion,
                            cast(:payload as jsonb), :idempotencyKey, :producerKey, :createdAt)
                        on conflict (idempotency_key) do nothing
                        """)
                .param("id", stableId("rejected:" + envelope.messageId()))
                .param("aggregateId", request.participationId()).param("eventType", REJECTED_TYPE)
                .param("schemaVersion", REJECTED_SCHEMA).param("payload", write(payload))
                .param("idempotencyKey", "ROOM_ACCOUNT_REJECTED:" + envelope.messageId())
                .param("producerKey", envelope.producerKey()).param("createdAt", offset(clock.instant())).update();
    }

    private void auditConflict(Envelope envelope, String reason) {
        UUID auditId = stableId("audit:" + envelope.messageId() + ":" + reason);
        jdbc.sql("""
                        insert into operations.audit_events (
                            id, actor_type, actor_id, action_type, target_domain, target_id,
                            reason_code, correlation_id, idempotency_key, before_hash, occurred_at)
                        values (:id, 'SYSTEM', :actor, 'ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED',
                            'trading', :target, :reason, :correlation, :key, :payloadHash, :at)
                        on conflict (idempotency_key) do nothing
                        """)
                .param("id", auditId).param("actor", SYSTEM_ACTOR).param("target", envelope.messageId())
                .param("reason", reason).param("correlation", envelope.messageId())
                .param("key", "ROOM_ACCOUNT_REJECTED:" + envelope.messageId())
                .param("payloadHash", envelope.payloadHash()).param("at", offset(clock.instant())).update();
    }

    private static UUID uuid(JsonNode root, String name) {
        return UUID.fromString(text(root, name));
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw permanent("MISSING_" + name.toUpperCase(Locale.ROOT));
        return value.textValue();
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception impossible) { throw new IllegalStateException("unable to serialize room account fact", impossible); }
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime offset(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static PermanentFailure permanent(String reason) { return new PermanentFailure(reason); }

    private record Claim(UUID messageId, UUID token, int attempt) {}
    private record Envelope(UUID messageId, UUID aggregateId, String eventType, String schemaVersion,
            String producerKey, String payloadHash, String payloadDocument) {}

    private record Request(UUID commandId, UUID messageId, String producerIdempotencyKey,
            UUID roomId, UUID participationId, UUID botId, UUID evaluationSegmentId,
            String initialCashText, String currency, UUID feePolicyVersionId,
            UUID buyingPowerPolicyVersionId, Instant effectiveAt) {
        private Request {
            if (!MONEY.matcher(initialCashText).matches()) throw permanent("INVALID_INITIAL_CASH");
            BigDecimal amount = new BigDecimal(initialCashText);
            if (amount.signum() <= 0 || amount.scale() > 8) throw permanent("INVALID_INITIAL_CASH");
            if (!CURRENCY.matcher(currency).matches()) throw permanent("INVALID_CURRENCY");
        }
        BigDecimal initialCash() { return new BigDecimal(initialCashText); }
    }

    private static final class PermanentFailure extends RuntimeException {
        private final String reasonCode;
        private PermanentFailure(String reasonCode) { super(reasonCode); this.reasonCode = reasonCode; }
    }
}
