package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.strategy.runtime.control.StrategyBotSnapshotSource;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the {@code strategy-bot.v1} compiled plan B published for a bot at release time.
 *
 * <p>{@code bot.launch_contract_plans} holds one row per bot, written in the same transaction as the
 * launch snapshot whose hash the plan pins. A bot with no row is reported as an absent plan and the
 * consumer refuses the command with {@code SNAPSHOT_NOT_FOUND}, which is the correct answer rather
 * than a failure mode: a bot released before this contract had a producer has no plan this runtime
 * could load, and starting it on a guess would evaluate a strategy nobody released.
 *
 * <p>The document is returned verbatim. Every field rule, the snapshot hash and the plan checksum are
 * the codec's to verify, and it recomputes the checksum from the fields it decoded rather than
 * trusting the stored value — so a document that disagrees with its own checksum is refused here in
 * the consumer, not discovered when the bot trades.
 */
public final class PostgresStrategyBotSnapshotSource implements StrategyBotSnapshotSource {

    private final JdbcClient jdbc;

    public PostgresStrategyBotSnapshotSource(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<String> findCompiledPlan(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        return jdbc.sql("select plan_document::text from bot.launch_contract_plans where bot_id = :botId")
                .param("botId", botId)
                .query(String.class)
                .optional();
    }
}
