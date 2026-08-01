package com.idea2strategy.trading.strategy.runtime.candidate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BasicCandidateResolution(
        UUID candidateId,
        CandidateResolutionStatus status,
        List<CandidateResolutionReason> reasons,
        int occurrenceCount,
        List<String> relatedFlowIds) {

    public BasicCandidateResolution {
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
        if (reasons.isEmpty() || reasons.stream().anyMatch(Objects::isNull)
                || reasons.stream().distinct().count() != reasons.size()) {
            throw new IllegalArgumentException("reasons must be non-empty, unique, and non-null");
        }
        if (occurrenceCount < 1) {
            throw new IllegalArgumentException("occurrenceCount must be positive");
        }
        relatedFlowIds = Objects.requireNonNull(relatedFlowIds, "relatedFlowIds must not be null").stream()
                .map(flowId -> CandidateValueValidation.requireText(flowId, "relatedFlowId"))
                .distinct()
                .sorted()
                .toList();
        if (relatedFlowIds.isEmpty()) {
            throw new IllegalArgumentException("relatedFlowIds must not be empty");
        }
        boolean hasRejection = reasons.contains(CandidateResolutionReason.CANDIDATE_IDENTITY_CONFLICT)
                || reasons.contains(CandidateResolutionReason.OPPOSING_ACTION_CONFLICT);
        if ((status == CandidateResolutionStatus.REJECTED) != hasRejection) {
            throw new IllegalArgumentException("resolution status and reason do not agree");
        }
    }

    public CandidateResolutionReason reason() {
        return reasons.getLast();
    }
}
