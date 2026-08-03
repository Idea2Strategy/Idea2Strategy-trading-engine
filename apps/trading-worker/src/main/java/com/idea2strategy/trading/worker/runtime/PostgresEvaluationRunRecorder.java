package com.idea2strategy.trading.worker.runtime;

import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes the two canonical rows an evaluation's intent batch cannot exist without.
 *
 * <p>{@code trading.order_intents} carries a composite foreign key to {@code bot.evaluation_runs}, and
 * an intent batch names an official {@code bot.bot_events} row as its source — the partition of one
 * official event being the trading isolation boundary the batch is keyed by. C13's own evaluation
 * record is that {@code evaluation_runs} row, so this is not bookkeeping added for F's benefit; it is
 * the judgment record the evaluation already owed.
 *
 * <p>Both are idempotent on the market event. The bot event's idempotency key is derived from it, so
 * {@link BotEventStore#appendOrLoad} returns the same event for a redelivery, and the evaluation run
 * is inserted {@code on conflict do nothing} under the evaluation id the runtime derived from the same
 * event. A re-fed event therefore names the identical source event and evaluation run, which is what
 * lets the batch downstream be recognised as a duplicate rather than composed twice.
 */
public class PostgresEvaluationRunRecorder implements EvaluatingBotRuntime.EvaluationRunRecorder {

    /**
     * {@code trigger_event_id} is the official event, and {@code (trigger_event_id, flow_id)} is
     * unique — one flow evaluates a given event once, which is exactly the property being recorded.
     */
    private static final String RECORD_RUN = """
            insert into bot.evaluation_runs (
                id, bot_id, partition_id, flow_id, trigger_event_id, status, queued_at)
            values (:id, :botId, :partitionId, :flowId, :triggerEventId, 'RUNNING', :queuedAt)
            on conflict (id) do nothing
            """;

    private final JdbcClient jdbc;
    private final BotEventStore events;

    public PostgresEvaluationRunRecorder(JdbcClient jdbc, BotEventStore events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public UUID recordEvaluationRun(
            UUID botId,
            EvaluatingBotRuntime.BotScope scope,
            UUID evaluationId,
            MarketEventEnvelope event) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(event, "event");

        BotEvent official = events.appendOrLoad(new BotEventAppend(
                botId,
                BotEventType.EVALUATION_COMPLETED,
                "evaluation:" + evaluationId,
                evaluationId,
                null,
                event.occurredAt(),
                event.receivedAt(),
                "{\"marketEventId\":\"" + event.eventId() + "\",\"evaluationId\":\""
                        + evaluationId + "\"}"));

        jdbc.sql(RECORD_RUN)
                .param("id", evaluationId)
                .param("botId", botId)
                .param("partitionId", scope.partitionId())
                .param("flowId", scope.flowId())
                .param("triggerEventId", official.eventId())
                .param("queuedAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
        return official.eventId();
    }
}
