package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The current canonical budget row of one bot, as the store boundary accepts it.
 *
 * <p>Maps one to one onto {@code trading.bot_budget_projections}. The five amounts arrive already
 * computed: canonical says they are derived from the official ledger, the active resource
 * reservations and the current liquidatable valuation, and none of those derivations happen here.
 * What this record adds is the canonical shape — one currency, scale 8, no negative figure, an
 * event sequence the answer is true as of, and a digest of the whole row.
 *
 * <p>This mirrors {@code OrderPlacement} and {@code LotOpening}: the facts canonical additionally
 * requires are taken at the store boundary rather than by widening something that already exists.
 * Here there is no predecessor at all — the private schema had no budget table — so the wrapper is
 * the whole input.
 */
public record BotBudgetProjection(
        UUID botId,
        String currencyCode,
        BigDecimal availableCashAmount,
        BigDecimal activeReservationAmount,
        BigDecimal investedAmount,
        BigDecimal segregatedShortProceedsAmount,
        BigDecimal shortCollateralAmount,
        Instant valuationAt,
        String valuationStatus,
        long lastEventSequence) implements BudgetProjection {

    public BotBudgetProjection {
        botId = BudgetProjectionValues.required(botId, "botId");
        currencyCode = BudgetProjectionValues.currencyCode(currencyCode);
        availableCashAmount =
                BudgetProjectionValues.nonNegative(availableCashAmount, "availableCashAmount");
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

    /** Canonical {@code bot_budget_projections.bot_id}, which is also its primary key. */
    @Override
    public UUID projectionId() {
        return botId;
    }

    @Override
    public String projectionHash() {
        return BudgetProjectionValues.hash(
                "bot-budget-projection:v1",
                botId.toString(),
                currencyCode,
                availableCashAmount.toPlainString(),
                activeReservationAmount.toPlainString(),
                investedAmount.toPlainString(),
                segregatedShortProceedsAmount.toPlainString(),
                shortCollateralAmount.toPlainString(),
                valuationAt.toString(),
                valuationStatus,
                Long.toString(lastEventSequence));
    }
}
