package com.idea2strategy.trading.strategy.runtime.revalidation;

import com.idea2strategy.trading.strategy.runtime.candidate.BasicOrderCandidate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicCandidateRevalidationResult(
        UUID evaluationId,
        List<BasicOrderCandidate> validCandidates,
        List<DiscardedBasicCandidate> discardedCandidates,
        List<UUID> reevaluationInstrumentIds) {

    public BasicCandidateRevalidationResult {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        validCandidates = List.copyOf(Objects.requireNonNull(validCandidates, "validCandidates must not be null"));
        discardedCandidates = List.copyOf(Objects.requireNonNull(
                discardedCandidates, "discardedCandidates must not be null"));
        reevaluationInstrumentIds = List.copyOf(Objects.requireNonNull(
                reevaluationInstrumentIds, "reevaluationInstrumentIds must not be null"));
    }
}
