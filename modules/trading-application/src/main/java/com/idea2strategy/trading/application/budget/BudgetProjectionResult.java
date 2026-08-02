package com.idea2strategy.trading.application.budget;

import java.util.Objects;
import java.util.UUID;

/**
 * What one canonical budget row holds after a projection write.
 *
 * @param projectionId the bot or the partition the row is keyed by
 * @param lastEventSequence the event sequence the stored row is now the answer as of
 * @param projectionHash the stored {@code projection_hash}
 * @param outcome whether the row was created, advanced, or already held this projection
 */
public record BudgetProjectionResult(
        UUID projectionId,
        long lastEventSequence,
        String projectionHash,
        BudgetProjectionOutcome outcome) {

    public BudgetProjectionResult {
        Objects.requireNonNull(projectionId, "projectionId");
        Objects.requireNonNull(projectionHash, "projectionHash");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** True when the write left the canonical row exactly as it found it. */
    public boolean replayed() {
        return outcome == BudgetProjectionOutcome.UNCHANGED;
    }
}
