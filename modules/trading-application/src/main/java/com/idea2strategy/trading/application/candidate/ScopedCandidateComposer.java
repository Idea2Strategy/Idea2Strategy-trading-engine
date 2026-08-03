package com.idea2strategy.trading.application.candidate;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;

/**
 * Composes one scoped candidate batch into its canonical consequences: the order intent batch with
 * a decision per candidate, an accepted order per executable intent, and the resource reservation
 * each order's fill will draw on.
 *
 * <p>This is the production half the fake per-candidate pipeline stood in for. It is batch-shaped
 * on purpose: budget affordability is decided across all of a batch's buys together (the equal
 * allocation and the exceptional proportional reduction are batch-level rules), so a per-candidate
 * port could never express it.
 *
 * <p>Implementations must converge under redelivery: composing the same batch twice leaves exactly
 * the rows the first composition left, because every identity is derived from the batch's own
 * evaluation, never from a clock or a random value.
 */
public interface ScopedCandidateComposer {

    ScopedCompositionResult compose(CandidateBatch batch);
}
