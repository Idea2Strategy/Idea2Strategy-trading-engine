package com.idea2strategy.trading.strategy.runtime.plan;

import java.util.Objects;
import java.util.UUID;

public final class LockedExecutionPlanLoader {
    private final ExecutionPlanSource source;
    private final ExecutionPlanCompatibility compatibility;

    public LockedExecutionPlanLoader(ExecutionPlanSource source, ExecutionPlanCompatibility compatibility) {
        this.source = Objects.requireNonNull(source, "source");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
    }

    public LoadedExecutionPlan load(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        ExecutionPlanSourceSnapshot sourceSnapshot = source.findByBotId(botId)
                .orElseThrow(() -> failure(ExecutionPlanLoadFailure.SNAPSHOT_NOT_FOUND, botId.toString()));
        ReleasedExecutionPlanSnapshot plan = sourceSnapshot.plan();
        RuntimeStateSnapshot runtimeState = sourceSnapshot.runtimeState();

        if (!plan.locked()) {
            throw failure(ExecutionPlanLoadFailure.PLAN_NOT_LOCKED, plan.releaseId().toString());
        }
        if (!ExecutionPlanIntegrity.planMatches(plan)) {
            throw failure(ExecutionPlanLoadFailure.PLAN_INTEGRITY_MISMATCH, plan.releaseId().toString());
        }
        if (!plan.botId().equals(botId)) {
            throw failure(ExecutionPlanLoadFailure.PLAN_IDENTITY_MISMATCH, plan.botId().toString());
        }
        if (!plan.planSchemaVersion().equals(compatibility.planSchemaVersion())) {
            throw failure(ExecutionPlanLoadFailure.PLAN_SCHEMA_VERSION_MISMATCH,
                    plan.planSchemaVersion() + " != " + compatibility.planSchemaVersion());
        }
        plan.requiredFeatures().forEach(requirement -> {
            String supportedVersion = compatibility.supportedFeatureVersions().get(requirement.featureId());
            if (!requirement.version().equals(supportedVersion)) {
                throw failure(ExecutionPlanLoadFailure.FEATURE_VERSION_MISMATCH,
                        requirement.featureId() + "@" + requirement.version());
            }
        });

        if (!ExecutionPlanIntegrity.runtimeStateMatches(runtimeState)) {
            throw failure(ExecutionPlanLoadFailure.RUNTIME_INTEGRITY_MISMATCH, plan.releaseId().toString());
        }
        if (!runtimeState.botId().equals(botId) || !runtimeState.releaseId().equals(plan.releaseId())) {
            throw failure(ExecutionPlanLoadFailure.RUNTIME_IDENTITY_MISMATCH,
                    runtimeState.botId() + "/" + runtimeState.releaseId());
        }
        if (!runtimeState.runtimeSchemaVersion().equals(compatibility.runtimeSchemaVersion())) {
            throw failure(ExecutionPlanLoadFailure.RUNTIME_SCHEMA_VERSION_MISMATCH,
                    runtimeState.runtimeSchemaVersion() + " != " + compatibility.runtimeSchemaVersion());
        }

        return new LoadedExecutionPlan(
                plan.botId(),
                plan.releaseId(),
                plan.planSchemaVersion(),
                plan.requiredFeatures(),
                plan.planPayload(),
                runtimeState.runtimeSchemaVersion(),
                runtimeState.sequence(),
                runtimeState.values());
    }

    private static ExecutionPlanLoadException failure(ExecutionPlanLoadFailure failure, String detail) {
        return new ExecutionPlanLoadException(failure, detail);
    }
}
