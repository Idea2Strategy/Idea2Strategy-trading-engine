package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanSourceSnapshot;
import com.idea2strategy.trading.strategy.runtime.plan.ReleasedExecutionPlanSnapshot;
import com.idea2strategy.trading.strategy.runtime.plan.RuntimeStateSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StrategyBotExecutionPlanAdapter {
    public static final String RUNTIME_SCHEMA_VERSION = "strategy-bot-runtime.v1";

    ExecutionPlanSourceSnapshot adapt(UUID botId, StrategyBotCompiledPlan compiledPlan) {
        UUID releaseId = UUID.nameUUIDFromBytes(
                ("strategy-bot-release:" + botId + ":" + compiledPlan.snapshotHash())
                        .getBytes(StandardCharsets.UTF_8));
        ReleasedExecutionPlanSnapshot plan = ReleasedExecutionPlanSnapshot.locked(
                botId,
                releaseId,
                compiledPlan.schemaVersion(),
                Set.of(),
                compiledPlan.payloadDocument());
        RuntimeStateSnapshot runtimeState = RuntimeStateSnapshot.signed(
                botId,
                releaseId,
                RUNTIME_SCHEMA_VERSION,
                0,
                Map.of(
                        "snapshotHash", compiledPlan.snapshotHash(),
                        "planChecksum", compiledPlan.planChecksum()));
        return new ExecutionPlanSourceSnapshot(plan, runtimeState);
    }
}
