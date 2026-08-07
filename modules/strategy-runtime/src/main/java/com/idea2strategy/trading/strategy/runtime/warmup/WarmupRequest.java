package com.idea2strategy.trading.strategy.runtime.warmup;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record WarmupRequest(
        UUID botId,
        UUID releaseId,
        Instant startupTime,
        Set<WarmupRequirement> requirements) {

    public WarmupRequest {
        botId = Objects.requireNonNull(botId, "botId");
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        startupTime = Objects.requireNonNull(startupTime, "startupTime");
        requirements = Set.copyOf(Objects.requireNonNull(requirements, "requirements"));
        Set<String> ids = new HashSet<>();
        if (requirements.stream().anyMatch(requirement -> !ids.add(requirement.requirementId()))) {
            throw new IllegalArgumentException("requirementId must be unique");
        }
    }
}
