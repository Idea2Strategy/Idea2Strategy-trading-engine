package com.idea2strategy.trading.domain.shorting;

import java.time.Instant;
import java.util.UUID;

public record ShortEligibilitySnapshot(
        UUID instrumentId,
        boolean borrowable,
        boolean easyToBorrow,
        String locateEvidenceId,
        String snapshotVersion,
        Instant observedAt) {
    public ShortEligibilitySnapshot {
        instrumentId = ShortInputs.required(instrumentId, "instrumentId");
        snapshotVersion = ShortInputs.text(snapshotVersion, "snapshotVersion");
        observedAt = ShortInputs.required(observedAt, "observedAt");
        if (locateEvidenceId != null) locateEvidenceId = ShortInputs.text(locateEvidenceId, "locateEvidenceId");
    }

    public boolean hasLocateEvidence() {
        return locateEvidenceId != null;
    }
}
