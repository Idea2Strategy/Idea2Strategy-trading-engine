package com.idea2strategy.trading.domain.execution;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record FillDecision(
        UUID decisionId,
        String requestFingerprint,
        UUID orderId,
        long orderVersion,
        RecordedMarketSnapshot snapshot,
        FillEligibility eligibility,
        VirtualFill value,
        Instant evaluatedAt) {
    public FillDecision {
        if (decisionId == null || orderId == null || snapshot == null || eligibility == null || evaluatedAt == null) {
            throw new IllegalArgumentException("fill decision fields must not be null");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("requestFingerprint must not be blank");
        }
        if (orderVersion < 1) throw new IllegalArgumentException("orderVersion must be positive");
        if ((eligibility == FillEligibility.ELIGIBLE) != (value != null)) {
            throw new IllegalArgumentException("only an eligible decision carries a fill");
        }
    }

    public Optional<VirtualFill> fill() {
        return Optional.ofNullable(value);
    }

    public UUID snapshotId() {
        return snapshot.snapshotId();
    }
}
