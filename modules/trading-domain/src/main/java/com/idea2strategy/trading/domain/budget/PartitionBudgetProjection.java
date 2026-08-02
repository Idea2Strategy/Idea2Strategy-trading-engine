package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The current canonical budget row of one partition, as the store boundary accepts it.
 *
 * <p>Maps one to one onto {@code trading.partition_budget_projections}. Canonical carries
 * {@code bot_id} alongside {@code partition_id} and points the foreign key at
 * {@code bot.bot_partitions (bot_id, id)}, so a partition row cannot be attributed to a bot that
 * does not own the partition; the record keeps both ids for the same reason.
 *
 * <p>{@code budget_cap_amount} is a supplied figure like the rest. Canonical keeps the partition's
 * share as {@code bot.bot_partitions.budget_cap_bps} — basis points of the bot's initial capital —
 * and turning that into an amount is a product rule about which capital base applies. It is
 * therefore taken as an input and only checked for the canonical
 * {@code partition_budget_cap_positive}.
 */
public record PartitionBudgetProjection(
        UUID partitionId,
        UUID botId,
        String currencyCode,
        BigDecimal budgetCapAmount,
        BigDecimal activeReservationAmount,
        BigDecimal investedAmount,
        BigDecimal segregatedShortProceedsAmount,
        BigDecimal shortCollateralAmount,
        Instant valuationAt,
        String valuationStatus,
        long lastEventSequence) implements BudgetProjection {

    public PartitionBudgetProjection {
        partitionId = BudgetProjectionValues.required(partitionId, "partitionId");
        botId = BudgetProjectionValues.required(botId, "botId");
        currencyCode = BudgetProjectionValues.currencyCode(currencyCode);
        budgetCapAmount = BudgetProjectionValues.positive(budgetCapAmount, "budgetCapAmount");
        activeReservationAmount = BudgetProjectionValues.nonNegative(
                activeReservationAmount, "activeReservationAmount");
        investedAmount = BudgetProjectionValues.nonNegative(investedAmount, "investedAmount");
        segregatedShortProceedsAmount = BudgetProjectionValues.nonNegative(
                segregatedShortProceedsAmount, "segregatedShortProceedsAmount");
        shortCollateralAmount = BudgetProjectionValues.nonNegative(
                shortCollateralAmount, "shortCollateralAmount");
        valuationAt = BudgetProjectionValues.valuationAt(valuationAt);
        valuationStatus = BudgetProjectionValues.valuationStatus(valuationStatus);
        lastEventSequence = BudgetProjectionValues.eventSequence(lastEventSequence);
    }

    /** Canonical {@code partition_budget_projections.partition_id}, which is its primary key. */
    @Override
    public UUID projectionId() {
        return partitionId;
    }

    @Override
    public String projectionHash() {
        return BudgetProjectionValues.hash(
                "partition-budget-projection:v1",
                partitionId.toString(),
                botId.toString(),
                currencyCode,
                budgetCapAmount.toPlainString(),
                activeReservationAmount.toPlainString(),
                investedAmount.toPlainString(),
                segregatedShortProceedsAmount.toPlainString(),
                shortCollateralAmount.toPlainString(),
                valuationAt.toString(),
                valuationStatus,
                Long.toString(lastEventSequence));
    }
}
