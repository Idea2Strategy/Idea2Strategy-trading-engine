package com.idea2strategy.trading.strategy.runtime.revalidation;

import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConvergenceResult;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicOrderCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

public final class BasicCandidateRevalidator {
    private static final Comparator<BasicOrderCandidate> CANDIDATE_ORDER = Comparator
            .comparing(BasicOrderCandidate::instrumentId)
            .thenComparing(BasicOrderCandidate::candidateId);

    public BasicCandidateRevalidationResult revalidate(
            BasicCandidateConvergenceResult convergence,
            BasicEvaluationSnapshot evaluatedAt,
            BasicCurrentSnapshot current) {
        Objects.requireNonNull(convergence, "convergence must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(current, "current must not be null");
        if (!convergence.evaluationId().equals(evaluatedAt.evaluationId())) {
            throw new IllegalArgumentException("convergence and evaluation snapshot must have the same evaluationId");
        }

        List<RevalidationReason> globalReasons = globalReasons(evaluatedAt, current);
        List<BasicOrderCandidate> valid = new ArrayList<>();
        List<DiscardedBasicCandidate> discarded = new ArrayList<>();
        TreeSet<UUID> reevaluationInstruments = new TreeSet<>();

        convergence.acceptedCandidates().stream().sorted(CANDIDATE_ORDER).forEach(candidate -> {
            List<RevalidationReason> reasons = new ArrayList<>();
            addInstrumentReasons(candidate.instrumentId(), evaluatedAt, current, reasons);
            reasons.addAll(globalReasons);
            if (reasons.isEmpty()) {
                valid.add(candidate);
            } else {
                discarded.add(new DiscardedBasicCandidate(
                        candidate.candidateId(), candidate.instrumentId(), reasons));
                reevaluationInstruments.add(candidate.instrumentId());
            }
        });

        return new BasicCandidateRevalidationResult(
                convergence.evaluationId(), valid, discarded, List.copyOf(reevaluationInstruments));
    }

    private static List<RevalidationReason> globalReasons(
            BasicEvaluationSnapshot evaluatedAt,
            BasicCurrentSnapshot current) {
        List<RevalidationReason> reasons = new ArrayList<>();
        if (current.budgetVersion().isEmpty()) {
            reasons.add(RevalidationReason.BUDGET_STATE_MISSING);
        } else if (!current.budgetVersion().orElseThrow().equals(evaluatedAt.budgetVersion())) {
            reasons.add(RevalidationReason.BUDGET_CHANGED);
        }
        if (current.runtimeStateVersion().isEmpty()) {
            reasons.add(RevalidationReason.RUNTIME_STATE_MISSING);
        } else if (current.runtimeStateVersion().getAsLong() != evaluatedAt.runtimeStateVersion()) {
            reasons.add(RevalidationReason.RUNTIME_STATE_CHANGED);
        }
        if (current.runtimeStatus() != BasicBotRuntimeStatus.RUNNING) {
            reasons.add(RevalidationReason.BOT_NOT_RUNNING);
        }
        return List.copyOf(reasons);
    }

    private static void addInstrumentReasons(
            UUID instrumentId,
            BasicEvaluationSnapshot evaluatedAt,
            BasicCurrentSnapshot current,
            List<RevalidationReason> reasons) {
        String evaluatedMarketVersion = evaluatedAt.marketVersions().get(instrumentId);
        String currentMarketVersion = current.marketVersions().get(instrumentId);
        if (evaluatedMarketVersion == null) {
            reasons.add(RevalidationReason.EVALUATION_MARKET_STATE_MISSING);
        } else if (currentMarketVersion == null) {
            reasons.add(RevalidationReason.MARKET_STATE_MISSING);
        } else if (!currentMarketVersion.equals(evaluatedMarketVersion)) {
            reasons.add(RevalidationReason.MARKET_CHANGED);
        }

        String evaluatedPositionVersion = evaluatedAt.positionVersions().get(instrumentId);
        String currentPositionVersion = current.positionVersions().get(instrumentId);
        if (evaluatedPositionVersion == null) {
            reasons.add(RevalidationReason.EVALUATION_POSITION_STATE_MISSING);
        } else if (currentPositionVersion == null) {
            reasons.add(RevalidationReason.POSITION_STATE_MISSING);
        } else if (!currentPositionVersion.equals(evaluatedPositionVersion)) {
            reasons.add(RevalidationReason.POSITION_CHANGED);
        }
    }
}
