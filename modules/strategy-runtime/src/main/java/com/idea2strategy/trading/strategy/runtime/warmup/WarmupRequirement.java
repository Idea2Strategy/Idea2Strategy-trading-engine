package com.idea2strategy.trading.strategy.runtime.warmup;

import java.util.Objects;
import java.util.Set;

public record WarmupRequirement(
        String requirementId,
        String featureId,
        String featureVersion,
        Set<String> instruments,
        String resolution,
        int requiredObservations) {

    public WarmupRequirement {
        requirementId = WarmupValueValidation.requireText(requirementId, "requirementId");
        featureId = WarmupValueValidation.requireText(featureId, "featureId");
        featureVersion = WarmupValueValidation.requireText(featureVersion, "featureVersion");
        instruments = Set.copyOf(Objects.requireNonNull(instruments, "instruments"));
        if (instruments.isEmpty() || instruments.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("instruments must contain non-blank values");
        }
        resolution = WarmupValueValidation.requireText(resolution, "resolution");
        if (requiredObservations <= 0) {
            throw new IllegalArgumentException("requiredObservations must be positive");
        }
    }
}
