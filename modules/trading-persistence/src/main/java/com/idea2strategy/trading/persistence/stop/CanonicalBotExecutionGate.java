package com.idea2strategy.trading.persistence.stop;

import com.idea2strategy.trading.application.port.BotExecutionGatePort;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.stop.StopStepResult;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The BLOCK_NEW_WORK step of a bot stop.
 *
 * <p>The durable fact is the settlement itself: it is already in {@code bot.bot_events} before this
 * step runs, and {@link com.idea2strategy.trading.application.candidate.CandidateBatchProcessor}
 * refuses new scoped candidate batches for any bot whose settlement
 * {@link BotStopSettlementStore#findActive(UUID)} still reports. So there is nothing extra to
 * write here — the step verifies that the fact the intake gate keys on is actually visible, which
 * is what protects the settlement from a torn write ordering rather than assuming it.
 */
@Component
public class CanonicalBotExecutionGate implements BotExecutionGatePort {

    private final BotStopSettlementStore settlements;

    public CanonicalBotExecutionGate(BotStopSettlementStore settlements) {
        this.settlements = Objects.requireNonNull(settlements, "settlements");
    }

    @Override
    public StopStepResult blockNewEvaluationAndOrders(UUID botId, UUID operationId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(operationId, "operationId");
        if (settlements.findActive(botId).isEmpty()) {
            // The settlement this step belongs to is not visible as active — either it was never
            // recorded or it already ended. Blocking on top of that would claim an enforcement that
            // does not exist, so the step retries until the fact it depends on is readable.
            return StopStepResult.retryable(
                    "no active stop settlement is visible for bot " + botId);
        }
        return StopStepResult.completed(
                "candidate intake refuses new scoped batches while the settlement is active");
    }
}
