package com.idea2strategy.trading.application.budget;

import java.util.List;
import java.util.Objects;

/** What one bot's budget rebuild left in canonical, bot row first. */
public record BudgetRebuildResult(
        BudgetProjectionResult bot, List<BudgetProjectionResult> partitions) {

    public BudgetRebuildResult {
        Objects.requireNonNull(bot, "bot");
        partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions"));
    }

    /** True when the whole rebuild found canonical already holding it. */
    public boolean replayed() {
        return bot.replayed() && partitions.stream().allMatch(BudgetProjectionResult::replayed);
    }
}
