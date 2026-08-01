package com.idea2strategy.trading.strategy.runtime.candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public final class BasicCandidateConverger {
    private static final Comparator<BasicOrderCandidate> CANDIDATE_ORDER = Comparator
            .comparing(BasicOrderCandidate::instrumentId)
            .thenComparing(BasicOrderCandidate::candidateId);

    public BasicCandidateConvergenceResult converge(
            UUID evaluationId,
            List<BasicOrderCandidate> candidates) {
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        List<BasicOrderCandidate> immutableCandidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates must not be null"));

        Map<UUID, List<BasicOrderCandidate>> byIdentity = immutableCandidates.stream()
                .collect(Collectors.groupingBy(
                        BasicOrderCandidate::candidateId,
                        TreeMap::new,
                        Collectors.toList()));
        List<BasicCandidateResolution> resolutions = new ArrayList<>();
        List<BasicOrderCandidate> identityValid = new ArrayList<>();
        Map<UUID, Integer> occurrenceCounts = new TreeMap<>();

        byIdentity.forEach((candidateId, occurrences) -> {
            BasicOrderCandidate canonical = occurrences.get(0);
            int occurrenceCount = occurrences.size();
            occurrenceCounts.put(candidateId, occurrenceCount);
            if (occurrences.stream().anyMatch(candidate -> !candidate.equals(canonical))) {
                resolutions.add(new BasicCandidateResolution(
                        candidateId,
                        CandidateResolutionStatus.REJECTED,
                        List.of(CandidateResolutionReason.CANDIDATE_IDENTITY_CONFLICT),
                        occurrenceCount,
                        flowIds(occurrences)));
            } else {
                identityValid.add(canonical);
            }
        });

        Map<UUID, List<BasicOrderCandidate>> byInstrument = identityValid.stream()
                .collect(Collectors.groupingBy(
                        BasicOrderCandidate::instrumentId,
                        TreeMap::new,
                        Collectors.toList()));
        List<BasicOrderCandidate> accepted = new ArrayList<>();
        byInstrument.forEach((instrumentId, instrumentCandidates) -> {
            List<BasicOrderCandidate> ordered = instrumentCandidates.stream().sorted(CANDIDATE_ORDER).toList();
            boolean opposingSides = ordered.stream()
                    .map(BasicOrderCandidate::side)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(
                            com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide.class)))
                    .size() > 1;
            List<String> relatedFlows = flowIds(ordered);
            for (BasicOrderCandidate candidate : ordered) {
                int occurrenceCount = occurrenceCounts.get(candidate.candidateId());
                if (opposingSides) {
                    resolutions.add(new BasicCandidateResolution(
                            candidate.candidateId(),
                            CandidateResolutionStatus.REJECTED,
                            occurrenceCount > 1
                                    ? List.of(
                                            CandidateResolutionReason.EXACT_DUPLICATE_COLLAPSED,
                                            CandidateResolutionReason.OPPOSING_ACTION_CONFLICT)
                                    : List.of(CandidateResolutionReason.OPPOSING_ACTION_CONFLICT),
                            occurrenceCount,
                            relatedFlows));
                } else {
                    accepted.add(candidate);
                    resolutions.add(new BasicCandidateResolution(
                            candidate.candidateId(),
                            CandidateResolutionStatus.ACCEPTED,
                            occurrenceCount > 1
                                    ? List.of(CandidateResolutionReason.EXACT_DUPLICATE_COLLAPSED)
                                    : List.of(CandidateResolutionReason.ACCEPTED_AS_SUBMITTED),
                            occurrenceCount,
                            List.of(candidate.flowId())));
                }
            }
        });

        accepted.sort(CANDIDATE_ORDER);
        resolutions.sort(Comparator.comparing(BasicCandidateResolution::candidateId));
        return new BasicCandidateConvergenceResult(evaluationId, accepted, resolutions);
    }

    private static List<String> flowIds(List<BasicOrderCandidate> candidates) {
        return candidates.stream().map(BasicOrderCandidate::flowId).distinct().sorted().toList();
    }
}
