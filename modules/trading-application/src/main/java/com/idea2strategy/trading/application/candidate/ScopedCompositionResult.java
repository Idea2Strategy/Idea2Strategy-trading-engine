package com.idea2strategy.trading.application.candidate;

import java.util.Objects;
import java.util.UUID;

/**
 * What one scoped composition produced, counted from the canonical rows it created or converged on.
 *
 * <p>The counts are observations, not intentions: a redelivered composition reports the same batch
 * id and the same counts because it found the same rows, which is the caller's evidence that the
 * second delivery added nothing.
 */
public record ScopedCompositionResult(
        UUID intentBatchId, int approved, int reduced, int rejected, int ordersComposed) {

    public ScopedCompositionResult {
        Objects.requireNonNull(intentBatchId, "intentBatchId");
        if (approved < 0 || reduced < 0 || rejected < 0 || ordersComposed < 0) {
            throw new IllegalArgumentException("composition counts must not be negative");
        }
    }
}
