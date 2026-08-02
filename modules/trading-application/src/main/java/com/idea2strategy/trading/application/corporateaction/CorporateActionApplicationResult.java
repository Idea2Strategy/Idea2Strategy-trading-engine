package com.idea2strategy.trading.application.corporateaction;

import java.util.UUID;

/**
 * What one corporate action moved for one bot.
 *
 * <p>{@code botId} is part of the result because canonical scopes the application to a bot: every
 * lot movement names an official event of the bot that owns the lot, so an instrument-wide sweep is
 * reported once per bot rather than once in total.
 */
public record CorporateActionApplicationResult(
        UUID actionId, UUID botId, int adjustedLots, int adjustedFlowPositions,
        String ledgerEffect) {
}
