package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Objects;

public record ExecutionPlanSourceSnapshot(
        ReleasedExecutionPlanSnapshot plan,
        RuntimeStateSnapshot runtimeState) {

    public ExecutionPlanSourceSnapshot {
        plan = Objects.requireNonNull(plan, "plan");
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }
}
