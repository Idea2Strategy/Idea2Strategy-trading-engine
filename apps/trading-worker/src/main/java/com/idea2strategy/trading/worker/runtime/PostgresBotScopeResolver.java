package com.idea2strategy.trading.worker.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Resolves a compiled plan's flow key to the canonical ids B's provisioning wrote.
 *
 * <p>B writes the plan's flow key as the flow's name — {@code derivedId(releaseId, "flow:" + key),
 * key} — so a name lookup scoped to the bot is the correspondence, and it is a lookup rather than a
 * re-derivation on purpose: recomputing B's private id formula here would couple this service to a
 * detail B is free to change.
 *
 * <p>The partition is not matched on the plan's partition key, because B stores the strategy's display
 * name there rather than the key. The flow row carries its own partition, so resolving the flow
 * resolves both — and the composite foreign key {@code (partition_id, flow_id)} the canonical intent
 * carries is then satisfied by construction.
 */
public class PostgresBotScopeResolver implements EvaluatingBotRuntime.BotScopeResolver {

    private static final String RESOLVE = """
            select flow.partition_id, flow.id as flow_id
            from bot.flows flow
            join bot.bot_partitions partition on partition.id = flow.partition_id
            where partition.bot_id = :botId and flow.name = :flowKey
            """;

    private final JdbcClient jdbc;

    public PostgresBotScopeResolver(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<EvaluatingBotRuntime.BotScope> resolve(UUID botId, String flowKey) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(flowKey, "flowKey");
        return jdbc.sql(RESOLVE)
                .param("botId", botId)
                .param("flowKey", flowKey)
                .query((rs, row) -> new EvaluatingBotRuntime.BotScope(
                        rs.getObject("partition_id", UUID.class),
                        rs.getObject("flow_id", UUID.class)))
                .optional();
    }
}
