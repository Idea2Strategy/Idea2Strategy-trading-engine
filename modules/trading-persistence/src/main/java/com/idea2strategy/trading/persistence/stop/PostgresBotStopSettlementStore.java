package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.event.BotEventConflictException;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopStep;
import com.idea2strategy.trading.domain.stop.SystemCloseAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bot stop settlement write path, on canonical tables.
 *
 * <p>Canonical storage has no settlement, attempt or event table for a stop. It has
 * {@code bot.bot_events}, which the write ownership tables assign to this service and whose
 * canonical note already lists {@code SETTLEMENT_FAILED} among the event types this service
 * appends, and it has {@code trading.system_close_actions} for the forced closes the liquidation
 * step generates. Those two are the whole write path here.
 *
 * <p>Three consequences shape the code.
 *
 * <ul>
 *   <li><b>The settlement is its newest event.</b> There is no row to update, so every transition
 *       appends a full snapshot and a read takes the highest {@code event_sequence} among the
 *       settlement event types. The private attempt and event tables shared a key, so they collapse
 *       into that one row rather than losing anything.
 *   <li><b>Concurrency is the canonical unique index, not a version column.</b> The key
 *       {@code BOT_STOP:<settlementId>:<version>} makes {@code (bot_id, idempotency_key)} refuse a
 *       second transition into a version that is already taken. That is what the private
 *       {@code where version = :expectedVersion} bought, enforced by the database instead of by a
 *       read.
 *   <li><b>One settlement per bot survives.</b> The private table had {@code bot_id} unique. Here
 *       the request key is derived from the bot alone, so a competing request for a bot that is
 *       already settling cannot create a second stream.
 * </ul>
 *
 * <p>This store never writes {@code bot.bots}. The lifecycle projection on that table belongs to
 * the backend service, and the write ownership tables put {@code bot.bots} there.
 */
@Repository
public class PostgresBotStopSettlementStore implements BotStopSettlementStore {

    /**
     * Reserved so a settlement key can never be mistaken for the Trigger Router's {@code PRICE:} or
     * {@code SCHEDULE:} keys, and so every key of this concern sorts and greps together.
     */
    private static final String KEY_PREFIX = "BOT_STOP:";

    private final BotEventStore events;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresBotStopSettlementStore(
            BotEventStore events, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.events = Objects.requireNonNull(events, "events");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public BotStopSettlement createOrLoad(BotStopSettlement desired) {
        Objects.requireNonNull(desired, "desired");
        return transactions.execute(status -> createOrLoadInTransaction(desired));
    }

    @Override
    public BotStopSettlement load(UUID settlementId) {
        Objects.requireNonNull(settlementId, "settlementId");
        return latestBySettlement(settlementId)
                .map(StoredSettlement::settlement)
                .orElseThrow(() -> new IllegalArgumentException("unknown settlementId"));
    }

    @Override
    public List<BotStopSettlement> loadRecoverable() {
        return jdbc.sql(RECOVERABLE)
                .query((rs, row) -> BotStopSettlementView.of(rs).toDomain())
                .list();
    }

    @Override
    public BotStopSettlement recordStep(
            BotStopSettlement current, StopStep step, StopStepResult result, Instant occurredAt) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (step != StopStep.LIQUIDATE_POSITIONS && !result.closeActions().isEmpty()) {
            throw new IllegalArgumentException("only LIQUIDATE_POSITIONS may carry system close actions");
        }
        return transactions.execute(status -> recordStepInTransaction(current, step, result, occurredAt));
    }

    private BotStopSettlement createOrLoadInTransaction(BotStopSettlement desired) {
        Optional<StoredSettlement> running = latestByBot(desired.botId());
        if (running.isPresent()) {
            return running.orElseThrow().settlement();
        }
        try {
            events.appendOrLoad(new BotEventAppend(
                    desired.botId(),
                    BotEventType.SETTLEMENT_REQUESTED,
                    KEY_PREFIX + desired.botId() + ":REQUESTED",
                    // The settlement identifier correlates every event of this settlement, which is
                    // what makes the whole procedure one query on an indexed column.
                    desired.settlementId(),
                    null,
                    desired.requestedAt(),
                    desired.requestedAt(),
                    StopSettlementDocument.requested(desired)));
        } catch (BotEventConflictException conflict) {
            // Another request won the race for this bot. The private table resolved the same race
            // with ON CONFLICT DO NOTHING followed by a read of the settlement the bot ended up
            // with, so the running settlement is returned rather than the requested one.
            return latestByBot(desired.botId())
                    .map(StoredSettlement::settlement)
                    .orElseThrow(() -> new IllegalStateException("stop request conflict", conflict));
        }
        return desired;
    }

    private BotStopSettlement recordStepInTransaction(
            BotStopSettlement expected, StopStep step, StopStepResult result, Instant occurredAt) {
        StoredSettlement stored = latestBySettlement(expected.settlementId())
                .orElseThrow(() -> new IllegalArgumentException("unknown settlementId"));
        if (stored.settlement().version() != expected.version()) {
            return stored.settlement();
        }
        BotStopSettlement current = stored.settlement();
        BotStopSettlement next = switch (result.status()) {
            case COMPLETED -> current.completed(step, occurredAt);
            case PARTIAL, RETRYABLE -> current.incomplete(step, occurredAt);
            case TERMINAL_FAILURE -> current.failed(step, result.detail(), occurredAt);
        };

        BotEvent event;
        try {
            event = events.appendOrLoad(transition(stored, next, step, result));
        } catch (BotEventConflictException conflict) {
            // The version this transition claims is already taken by different work. The private
            // path detected that as an update affecting no row and re-read; so does this one.
            return latestBySettlement(expected.settlementId())
                    .map(StoredSettlement::settlement)
                    .orElseThrow(() -> new IllegalStateException("stop step conflict", conflict));
        }
        recordCloseActions(event, next, result);
        return next;
    }

    private BotEventAppend transition(
            StoredSettlement stored, BotStopSettlement next, StopStep step, StopStepResult result) {
        StopCheckpoint from = stored.settlement().checkpoint();
        BotEventType type = switch (next.checkpoint()) {
            case STOPPED -> BotEventType.SETTLEMENT_COMPLETED;
            case SETTLEMENT_FAILED -> BotEventType.SETTLEMENT_FAILED;
            default -> BotEventType.SETTLEMENT_STEP_RECORDED;
        };
        return new BotEventAppend(
                next.botId(),
                type,
                KEY_PREFIX + next.settlementId() + ":" + next.version(),
                next.settlementId(),
                // The previous settlement event, so the stream carries the checkpoint chain the
                // private bot_stop_event.from_checkpoint column recorded, and carries it as a real
                // foreign key rather than as text.
                stored.eventId(),
                next.updatedAt(),
                next.updatedAt(),
                StopSettlementDocument.transition(next, from, step, result, next.operationId(step)));
    }

    /**
     * Writes the forced closes the liquidation step generated.
     *
     * <p>{@code source_event_id} is the settlement event this call just appended, so the close and
     * the checkpoint that caused it name each other. The canonical unique index on
     * {@code (bot_id, source_event_id, instrument_id, reason_type)} is what makes a redelivered
     * step land on the same rows: the settlement event is keyed by version, so replaying the step
     * reaches the same event and therefore the same conflict target.
     */
    private void recordCloseActions(BotEvent event, BotStopSettlement settlement, StopStepResult result) {
        for (SystemCloseAction action : result.closeActions()) {
            if (!action.botId().equals(settlement.botId())) {
                throw new IllegalArgumentException("system close action belongs to another bot");
            }
            jdbc.sql(INSERT_CLOSE_ACTION)
                    .param("id", action.actionId())
                    .param("bot", action.botId())
                    .param("partition", action.partitionId())
                    .param("flow", action.flowId())
                    .param("instrument", action.instrumentId())
                    .param("event", event.eventId())
                    .param("reason", action.reasonType().name())
                    .param("quantity", action.requestedQuantity())
                    .param("intent", action.generatedIntentId())
                    .param("document", action.reasonDocument())
                    .param("hash", action.calculationHash())
                    .param("createdAt", offset(settlement.updatedAt()))
                    .update();
        }
    }

    private Optional<StoredSettlement> latestByBot(UUID botId) {
        return jdbc.sql(LATEST_BY_BOT).param("bot", botId).query(PostgresBotStopSettlementStore::stored)
                .optional();
    }

    private Optional<StoredSettlement> latestBySettlement(UUID settlementId) {
        return jdbc.sql(LATEST_BY_SETTLEMENT).param("settlement", settlementId)
                .query(PostgresBotStopSettlementStore::stored).optional();
    }

    private static StoredSettlement stored(ResultSet rs, int row) throws SQLException {
        return new StoredSettlement(
                rs.getObject("id", UUID.class), BotStopSettlementView.of(rs).toDomain());
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    /** The newest settlement event plus its identifier, which the next transition chains to. */
    private record StoredSettlement(UUID eventId, BotStopSettlement settlement) {}

    private static final String LATEST_BY_BOT = """
            select id, %s
            from bot.bot_events
            where bot_id = :bot and event_type in (%s)
            order by event_sequence desc
            limit 1
            """.formatted(StopSettlementDocument.PROJECTION, StopSettlementDocument.EVENT_TYPES);

    /**
     * {@code correlation_id} is the settlement identifier and is indexed, so this stays a lookup
     * rather than a scan of every settlement document.
     */
    private static final String LATEST_BY_SETTLEMENT = """
            select id, %s
            from bot.bot_events
            where correlation_id = :settlement and event_type in (%s)
            order by event_sequence desc
            limit 1
            """.formatted(StopSettlementDocument.PROJECTION, StopSettlementDocument.EVENT_TYPES);

    private static final String RECOVERABLE = """
            select %s
            from (
                select distinct on (bot_id) bot_id, summary_document, event_sequence
                from bot.bot_events
                where event_type in (%s)
                order by bot_id, event_sequence desc
            ) latest
            where summary_document ->> 'checkpoint' not in (%s)
            order by summary_document ->> 'requestedAt', summary_document ->> 'settlementId'
            """.formatted(
            StopSettlementDocument.PROJECTION,
            StopSettlementDocument.EVENT_TYPES,
            StopSettlementDocument.TERMINAL_CHECKPOINTS);

    private static final String INSERT_CLOSE_ACTION = """
            insert into trading.system_close_actions (
                id, bot_id, partition_id, flow_id, instrument_id, source_event_id, reason_type,
                requested_quantity, generated_intent_id, reason_document, calculation_hash, created_at)
            values (:id, :bot, :partition, :flow, :instrument, :event,
                cast(:reason as trading.system_close_reason), :quantity, :intent,
                cast(:document as jsonb), :hash, :createdAt)
            on conflict (bot_id, source_event_id, instrument_id, reason_type) do nothing
            """;
}
