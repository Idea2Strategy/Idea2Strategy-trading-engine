package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable state of the bot stop settlement procedure.
 *
 * <p>Canonical storage has no settlement table. The procedure lives in {@code bot.bot_events},
 * which the write ownership tables assign to this service and whose canonical note already names
 * {@code SETTLEMENT_FAILED} as one of its own event types, and the forced closes the liquidation
 * step generates live in {@code trading.system_close_actions}. That makes the settlement an event
 * stream rather than a mutable row: the checkpoint reached is whatever the newest settlement event
 * of the bot says, and {@code (bot_id, idempotency_key)} is what stops a redelivered step from
 * being applied twice.
 */
public interface BotStopSettlementStore {

    /**
     * Records the request, or returns the settlement the bot already has.
     *
     * <p>One settlement per bot. A second request for a bot that is already settling returns the
     * running settlement rather than starting a competing one.
     */
    BotStopSettlement createOrLoad(BotStopSettlement desired);

    BotStopSettlement load(UUID settlementId);

    /**
     * The bot's settlement that has not reached a terminal checkpoint, if it has one.
     *
     * <p>This is the fact BLOCK_NEW_WORK is enforced against: from the moment a stop is requested
     * until the settlement ends, new work for the bot is refused at intake rather than raced.
     */
    Optional<BotStopSettlement> findActive(UUID botId);

    /** Settlements that have not reached a terminal checkpoint, oldest request first. */
    List<BotStopSettlement> loadRecoverable();

    /**
     * Advances the settlement by one step and returns the resulting state.
     *
     * <p>The caller's {@code current} is the state it acted on. If the settlement has already moved
     * past it the stored state is returned unchanged, so a step is never applied twice. Any forced
     * close the result carries is written in the same transaction and attributed to the settlement
     * event this call appends.
     */
    BotStopSettlement recordStep(
            BotStopSettlement current, StopStep step, StopStepResult result, Instant occurredAt);
}
