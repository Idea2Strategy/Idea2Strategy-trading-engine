package com.idea2strategy.trading.domain.budget;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One rebuild of a bot's budget: the bot row and the partition rows underneath it, as of one event.
 *
 * <p>Canonical states no relationship between {@code bot_budget_projections} and
 * {@code partition_budget_projections} — no trigger, no total, no shared key beyond the bot — and
 * none is invented here. What this record does say is weaker and purely structural: these rows were
 * produced by the same rebuild, so they belong to the same bot, name distinct partitions, and are
 * all the answer as of the same {@code last_event_sequence}. That is what makes writing them in one
 * transaction meaningful, and it is what lets a stale rebuild be refused as a whole rather than
 * leaving a bot row that has moved past its partitions.
 *
 * <p>Currencies are deliberately not required to agree. Canonical gives every row its own
 * {@code currency_code} and says nothing about a bot and its partitions sharing one.
 */
public record BudgetProjectionRebuild(
        BotBudgetProjection bot, List<PartitionBudgetProjection> partitions) {

    public BudgetProjectionRebuild {
        BudgetProjectionValues.required(bot, "bot");
        partitions = List.copyOf(BudgetProjectionValues.required(partitions, "partitions"));
        Set<UUID> seen = new HashSet<>();
        for (PartitionBudgetProjection partition : partitions) {
            if (!partition.botId().equals(bot.botId())) {
                throw new IllegalArgumentException(
                        "a rebuild may only carry partitions of the bot it rebuilds");
            }
            if (partition.lastEventSequence() != bot.lastEventSequence()) {
                throw new IllegalArgumentException(
                        "every row of one rebuild must be as of the same event sequence");
            }
            if (!seen.add(partition.partitionId())) {
                throw new IllegalArgumentException("a partition may appear in a rebuild only once");
            }
        }
    }

    public UUID botId() {
        return bot.botId();
    }

    public long lastEventSequence() {
        return bot.lastEventSequence();
    }
}
