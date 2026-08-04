package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import java.time.Instant;
import java.util.UUID;

public interface BotRuntimeLifecycle {

    /**
     * Registers a bot for evaluation within {@code window}.
     *
     * <p>The window is a value rather than a start instant because both of its ends are product
     * meaning. A room bot's evaluation closes when the room's schedule says so, and an implementation
     * that only knew when to begin would keep deciding past that boundary until a stop happened to
     * arrive — putting trades that belong to no room into the ledger the room's performance is read
     * from (C93).
     *
     * <p>{@code warmup} is the prepared history the startup gate resolved, and it is a parameter
     * rather than something the implementation re-reads because it is the only thing that can seed a
     * feature's window: a bounded-window feature has no value until it holds its required bars, and
     * a runtime that started with an empty window would report warm-up incomplete for its first
     * fifteen minutes of live data instead of evaluating immediately. The gate already fetched it, so
     * dropping it here and refetching later would be both slower and a different dataset.
     */
    void start(LoadedExecutionPlan plan, PreparedWarmup warmup, EvaluationWindow window);

    void stop(UUID botId, String reasonCode);
}
