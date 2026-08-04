package com.idea2strategy.trading.strategy.runtime.control;

import com.idea2strategy.trading.strategy.runtime.warmup.WarmupRequirement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

sealed interface StrategyBotCommand permits StrategyBotRunCommand, StrategyBotStopCommand {
    StrategyBotMetadata metadata();

    UUID botId();

    String expectedSnapshotHash();
}

record StrategyBotMetadata(
        String contractVersion,
        String messageType,
        UUID messageId,
        Instant occurredAt,
        UUID correlationId,
        String idempotencyKey) {
}

record StrategyBotRunCommand(
        StrategyBotMetadata metadata,
        UUID botId,
        String expectedSnapshotHash,
        Instant executionEligibleFrom,
        /** The room schedule's evaluation end, absent for a bot no schedule bounds. */
        Instant executionEligibleUntil) implements StrategyBotCommand {

    EvaluationWindow window() {
        return new EvaluationWindow(executionEligibleFrom, executionEligibleUntil);
    }
}

record StrategyBotStopCommand(
        StrategyBotMetadata metadata,
        UUID botId,
        String expectedSnapshotHash,
        String reasonCode) implements StrategyBotCommand {
}

record StrategyBotCompiledPlan(
        String contractVersion,
        String schemaVersion,
        String snapshotHash,
        String planChecksum,
        Set<WarmupRequirement> warmupRequirements,
        String payloadDocument) {
}
