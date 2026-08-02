package com.idea2strategy.trading.domain.corporateaction;

import java.util.Objects;
import java.util.UUID;

/**
 * An approved corporate action about to be applied to one bot's lots, as canonical records it.
 *
 * <p>{@link ApprovedCorporateAction} stays the approval object and is not widened. What canonical
 * needs on top of it is provenance and scope, so those are carried at the store boundary exactly as
 * {@code LotOpening} and {@code LotClosing} carry them for the position write path.
 *
 * <p>The scope is a <em>bot</em>, not an instrument. Canonical requires every
 * {@code trading.lot_movements} row to name an official {@code bot.bot_events} row of the bot that
 * owns the lot, so one instrument-wide sweep across every bot cannot exist: the action is applied
 * once per bot, against that bot's own event. Partitions need no further split, because an event
 * belongs to a bot and the partition of each movement comes from the lot it moves.
 */
public record CorporateActionApplication(
        ApprovedCorporateAction action, UUID botId, UUID botEventId) {

    public CorporateActionApplication {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(botEventId, "botEventId");
        if (action.numerator() == action.denominator()) {
            throw new IllegalArgumentException(
                    "a ratio that changes no quantity has no canonical movement to record");
        }
    }

    /** Canonical {@code lot_movements.id} of this lot's adjustment. */
    public UUID movementId(UUID positionLotId) {
        return CorporateActionIdentity.movementId(
                action.actionId(), positionLotId, botEventId);
    }
}
