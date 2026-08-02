package com.idea2strategy.trading.persistence.stop;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Reads the canonical shape of a bot stop settlement back.
 *
 * <p>Nothing here reconstructs state by folding the stream. The newest settlement event of a bot
 * carries the whole snapshot, and the attempt history is the same events read in order, so both
 * questions stay one query.
 */
public final class JooqBotStopSettlementQuery {

    private final DSLContext dsl;

    public JooqBotStopSettlementQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    /** The settlement as it currently stands. */
    public Optional<BotStopSettlementView> find(UUID settlementId) {
        return dsl.fetchOptional("""
                select %s
                from bot.bot_events
                where correlation_id = ? and event_type in (%s)
                order by event_sequence desc
                limit 1
                """.formatted(StopSettlementDocument.PROJECTION, StopSettlementDocument.EVENT_TYPES),
                settlementId)
                .map(JooqBotStopSettlementQuery::view);
    }

    /**
     * Every recorded step attempt, oldest first.
     *
     * <p>The request event carries no step, so it is excluded here exactly as the private attempt
     * table excluded it.
     */
    public List<AttemptView> attempts(UUID settlementId) {
        return dsl.fetch("""
                select cast(summary_document ->> 'version' as bigint) as version,
                       cast(summary_document ->> 'operationId' as uuid) as operation_id,
                       summary_document ->> 'step' as step,
                       summary_document ->> 'resultStatus' as result_status,
                       summary_document ->> 'resultDetail' as detail,
                       summary_document ->> 'fromCheckpoint' as from_checkpoint,
                       summary_document ->> 'checkpoint' as to_checkpoint,
                       occurred_at
                from bot.bot_events
                where correlation_id = ? and event_type in (%s)
                  and summary_document ->> 'step' is not null
                order by cast(summary_document ->> 'version' as bigint)
                """.formatted(StopSettlementDocument.EVENT_TYPES), settlementId)
                .map(row -> new AttemptView(
                        row.get("version", Long.class),
                        row.get("operation_id", UUID.class),
                        row.get("step", String.class),
                        row.get("result_status", String.class),
                        row.get("detail", String.class),
                        row.get("from_checkpoint", String.class),
                        row.get("to_checkpoint", String.class),
                        row.get("occurred_at", OffsetDateTime.class).toInstant()));
    }

    /** The forced closes a settlement recorded, in the order the canonical rows were created. */
    public List<CloseActionView> closeActions(UUID botId) {
        return dsl.fetch("""
                select id, partition_id, flow_id, instrument_id, source_event_id,
                       cast(reason_type as varchar) as reason_type, requested_quantity,
                       generated_intent_id, cast(reason_document as varchar) as reason_document,
                       calculation_hash, created_at
                from trading.system_close_actions
                where bot_id = ?
                order by created_at, instrument_id
                """, botId)
                .map(row -> new CloseActionView(
                        row.get("id", UUID.class),
                        row.get("partition_id", UUID.class),
                        row.get("flow_id", UUID.class),
                        row.get("instrument_id", UUID.class),
                        row.get("source_event_id", UUID.class),
                        row.get("reason_type", String.class),
                        row.get("requested_quantity", BigDecimal.class),
                        row.get("generated_intent_id", UUID.class),
                        row.get("reason_document", String.class),
                        row.get("calculation_hash", String.class),
                        row.get("created_at", OffsetDateTime.class).toInstant()));
    }

    private static BotStopSettlementView view(Record row) {
        return new BotStopSettlementView(
                UUID.fromString(row.get("settlement_id", String.class)),
                row.get("bot_id", UUID.class),
                row.get("stop_reason", String.class),
                row.get("reason_detail", String.class),
                row.get("checkpoint", String.class),
                row.get("version", Long.class),
                Instant.parse(row.get("requested_at", String.class)),
                Instant.parse(row.get("updated_at", String.class)),
                row.get("failed_step", String.class),
                row.get("terminal_reason", String.class));
    }

    /** One recorded step attempt, which also carries the checkpoint transition it produced. */
    public record AttemptView(
            long version, UUID operationId, String step, String status, String detail,
            String fromCheckpoint, String toCheckpoint, Instant occurredAt) {}

    /** One {@code trading.system_close_actions} row. */
    public record CloseActionView(
            UUID actionId, UUID partitionId, UUID flowId, UUID instrumentId, UUID sourceEventId,
            String reasonType, BigDecimal requestedQuantity, UUID generatedIntentId,
            String reasonDocument, String calculationHash, Instant createdAt) {}
}
