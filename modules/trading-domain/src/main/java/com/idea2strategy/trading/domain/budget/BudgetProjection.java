package com.idea2strategy.trading.domain.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One rebuildable canonical budget row, as the store boundary accepts it.
 *
 * <p>Canonical declares {@code trading.bot_budget_projections} and
 * {@code trading.partition_budget_projections} rebuildable read models: the official history is the
 * ledger, the active reservations and the position lots, and these rows are only the current answer
 * derived from them. This type is therefore <em>not</em> an accounting model. It carries amounts
 * that something else already computed and fixes the shape canonical insists on — currency, scale,
 * sign, the valuation the amounts were taken at, and the event sequence the answer is true as of.
 *
 * <p>What each amount <em>means</em> is deliberately not defined here. The derivation from the
 * ledger, from {@code trading.resource_reservations} and from the position projections belongs to
 * whatever owns those rules; persisting a supplied figure in the canonical shape does not.
 *
 * @see BotBudgetProjection
 * @see PartitionBudgetProjection
 */
public sealed interface BudgetProjection permits BotBudgetProjection, PartitionBudgetProjection {

    /** Canonical {@code numeric(24,8)} amounts. */
    int SCALE = 8;

    /** Canonical {@code valuation_status varchar(30)}. */
    int VALUATION_STATUS_MAX_LENGTH = 30;

    /** Owning bot. Canonical keeps it on both rows so the tenant scope survives on the partition. */
    UUID botId();

    /** The row this projection is the current state of: the bot, or the partition. */
    UUID projectionId();

    /** Canonical {@code char(3)} currency of every amount on the row. */
    String currencyCode();

    BigDecimal activeReservationAmount();

    BigDecimal investedAmount();

    BigDecimal segregatedShortProceedsAmount();

    BigDecimal shortCollateralAmount();

    /** When the valuation behind the amounts was taken. */
    Instant valuationAt();

    /** How that valuation went. Canonical stores free text here and constrains only the length. */
    String valuationStatus();

    /** The bot event sequence the amounts are the answer as of. */
    long lastEventSequence();

    /**
     * Canonical {@code projection_hash}.
     *
     * <p>A rebuildable row needs to be comparable with a rebuild of itself, and canonical gives it
     * nowhere else to prove that. The digest covers every stored value, so a re-projection at the
     * same event sequence is recognisable as the same answer instead of merely looking like one.
     */
    String projectionHash();
}
