package com.idea2strategy.trading.strategy.runtime.candidate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicCandidateConvergenceResult(
        UUID evaluationId,
        List<BasicOrderCandidate> acceptedCandidates,
        List<BasicCandidateResolution> resolutions) {

    public BasicCandidateConvergenceResult {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        acceptedCandidates = List.copyOf(Objects.requireNonNull(
                acceptedCandidates, "acceptedCandidates must not be null"));
        resolutions = List.copyOf(Objects.requireNonNull(resolutions, "resolutions must not be null"));
    }
}
