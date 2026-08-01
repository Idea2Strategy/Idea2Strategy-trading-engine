package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BotJudgmentSnapshot(
        UUID botId,
        long lastSequence,
        List<JudgmentEntry> entries,
        ProjectedRuntimeState runtimeState) {

    public BotJudgmentSnapshot {
        botId = Objects.requireNonNull(botId, "botId must not be null");
        if (lastSequence < 0) {
            throw new IllegalArgumentException("lastSequence must not be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState must not be null");
        if (lastSequence != entries.size()) {
            throw new IllegalArgumentException("lastSequence must match the contiguous entry count");
        }
    }
}
