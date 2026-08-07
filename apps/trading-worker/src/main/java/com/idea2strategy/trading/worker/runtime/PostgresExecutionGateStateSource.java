package com.idea2strategy.trading.worker.runtime;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Restores execution limits from immutable intents emitted in the current position cycle. */
final class PostgresExecutionGateStateSource
        implements EvaluatingBotRuntime.ExecutionGateStateSource {

    /**
     * A position cycle starts after the most recently closed long lot, or when the bot started if it
     * has never closed one. Counting intent rows deliberately matches the live gate: it limits
     * condition-qualified order attempts even when downstream composition rejects the attempt.
     */
    private static final String RESOLVE = """
            with cycle as (
                select greatest(
                           coalesce(max(projection.closed_at), '-infinity'::timestamptz),
                           coalesce(bot.started_at, bot.execution_eligible_from)
                       ) as started_at
                from bot.bots bot
                left join trading.position_lots lot
                  on lot.bot_id = bot.id
                 and lot.instrument_id = :instrumentId
                 and cast(lot.lot_side as varchar) = 'LONG'
                left join trading.position_lot_projections projection
                  on projection.position_lot_id = lot.id
                 and projection.closed_at is not null
                where bot.id = :botId
                group by bot.started_at, bot.execution_eligible_from
            )
            select count(intent.id) as executions,
                   max(run.queued_at) as last_execution_at
            from cycle
            join bot.bot_partitions partition on partition.bot_id = :botId
            join bot.flows flow
              on flow.partition_id = partition.id
             and flow.name = :flowKey
            left join trading.order_intents intent
              on intent.bot_id = :botId
             and intent.flow_id = flow.id
             and intent.instrument_id = :instrumentId
             and cast(intent.origin_type as varchar) = 'FLOW_EVALUATION'
            left join bot.evaluation_runs run
              on run.bot_id = intent.bot_id
             and run.id = intent.evaluation_run_id
             and run.queued_at >= cycle.started_at
            where intent.id is null or run.id is not null
            """;

    private final JdbcClient jdbc;

    PostgresExecutionGateStateSource(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public EvaluatingBotRuntime.ExecutionGateSnapshot resolve(
            UUID botId, String flowKey, UUID instrumentId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(flowKey, "flowKey");
        Objects.requireNonNull(instrumentId, "instrumentId");
        return jdbc.sql(RESOLVE)
                .param("botId", botId)
                .param("flowKey", flowKey)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> {
                    int executions = resultSet.getInt("executions");
                    OffsetDateTime last =
                            resultSet.getObject("last_execution_at", OffsetDateTime.class);
                    return executions == 0
                            ? EvaluatingBotRuntime.ExecutionGateSnapshot.empty()
                            : new EvaluatingBotRuntime.ExecutionGateSnapshot(
                                    executions, last.toInstant());
                })
                .optional()
                .orElseGet(EvaluatingBotRuntime.ExecutionGateSnapshot::empty);
    }
}
