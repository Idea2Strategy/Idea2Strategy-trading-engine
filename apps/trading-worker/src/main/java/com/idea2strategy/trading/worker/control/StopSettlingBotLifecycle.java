package com.idea2strategy.trading.worker.control;

import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.application.stop.RequestBotStopCommand;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * F91: B's stop command reaches the real order and settlement state.
 *
 * <p>{@code StrategyBotControlConsumer} verifies the command against the locked snapshot and its
 * own checkpoint, then hands the bot to this lifecycle. Stop becomes a durable
 * {@link BotStopOrchestrator} settlement — evaluation and order intake blocked, open orders
 * cancelled, reservations released, remaining positions liquidated — recorded step by step in the
 * canonical event stream, so a crash mid-stop is resumed by the recovery worker rather than lost
 * with the process.
 *
 * <p>The settlement is requested before the local runtime is told to halt. The other order loses
 * the stop if the process dies in between; this order at worst evaluates a few moments longer,
 * which the settlement's own BLOCK_NEW_WORK step then cuts off durably.
 *
 * <p>Start is delegated. The evaluation loop a start command resumes belongs to the strategy
 * runtime, and wiring it is B91/C90 territory; this class only guarantees that whatever lifecycle
 * it wraps, a stop settles.
 */
public final class StopSettlingBotLifecycle implements BotRuntimeLifecycle {

    private final BotStopOrchestrator orchestrator;
    private final BotRuntimeLifecycle delegate;
    private final Clock clock;

    public StopSettlingBotLifecycle(
            BotStopOrchestrator orchestrator, BotRuntimeLifecycle delegate, Clock clock) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void start(LoadedExecutionPlan plan, PreparedWarmup warmup, Instant executionEligibleFrom) {
        delegate.start(plan, warmup, executionEligibleFrom);
    }

    @Override
    public void stop(UUID botId, String reasonCode) {
        Objects.requireNonNull(botId, "botId");
        orchestrator.requestStop(new RequestBotStopCommand(
                botId,
                stopReason(reasonCode),
                reasonCode == null || reasonCode.isBlank() ? "BOT_STOP_COMMAND" : reasonCode,
                clock.instant()));
        delegate.stop(botId, reasonCode);
    }

    /**
     * B's reason codes onto the settlement's own vocabulary. The original code always survives
     * verbatim as the settlement detail, so an unmapped code loses nothing — it is merely filed
     * under the platform-initiated reason rather than inventing a new one.
     */
    private static StopReason stopReason(String reasonCode) {
        String code = reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT);
        if (code.contains("USER")) {
            return StopReason.USER_REQUEST;
        }
        if (code.contains("SUSPEND")) {
            return StopReason.ACCOUNT_SUSPENDED;
        }
        return StopReason.POLICY_FORCED;
    }
}
