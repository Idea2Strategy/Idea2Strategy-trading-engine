package com.idea2strategy.trading.strategy.runtime.revalidation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DiscardedBasicCandidate(
        UUID candidateId,
        UUID instrumentId,
        List<RevalidationReason> reasons) {

    public DiscardedBasicCandidate {
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        reasons = Objects.requireNonNull(reasons, "reasons must not be null").stream()
                .peek(reason -> Objects.requireNonNull(reason, "reason must not be null"))
                .distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
    }
}
