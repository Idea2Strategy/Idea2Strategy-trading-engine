package com.idea2strategy.trading.strategy.runtime.revalidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.EqualAllocationShare;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConverger;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicCandidateConvergenceResult;
import com.idea2strategy.trading.strategy.runtime.candidate.BasicOrderCandidate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicCandidateRevalidatorTest {
    private static final UUID EVALUATION_ID = UUID.fromString("5ce5ec67-9d06-478b-b8ba-6708cb7d1830");
    private static final UUID INSTRUMENT_A = UUID.fromString("00000000-0000-4000-8000-000000000401");
    private static final UUID INSTRUMENT_B = UUID.fromString("00000000-0000-4000-8000-000000000402");
    private static final UUID CANDIDATE_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Test
    void discardsOnlyTheCandidateWhoseMarketVersionChanged() {
        BasicOrderCandidate candidateA = buy(CANDIDATE_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate candidateB = buy(CANDIDATE_B_ID, "buy-b", INSTRUMENT_B);
        BasicCandidateConvergenceResult convergence = converge(candidateB, candidateA);

        BasicCandidateRevalidationResult result = new BasicCandidateRevalidator().revalidate(
                convergence,
                evaluated(Map.of(INSTRUMENT_A, "market-a-1", INSTRUMENT_B, "market-b-1")),
                current(
                        Map.of(INSTRUMENT_A, "market-a-2", INSTRUMENT_B, "market-b-1"),
                        Map.of(INSTRUMENT_A, "position-a-1", INSTRUMENT_B, "position-b-1"),
                        Optional.of("budget-1"), OptionalLong.of(7), BasicBotRuntimeStatus.RUNNING));

        assertEquals(List.of(candidateB), result.validCandidates());
        assertSame(candidateB, result.validCandidates().getFirst());
        assertEquals(List.of(INSTRUMENT_A), result.reevaluationInstrumentIds());
        assertEquals(List.of(RevalidationReason.MARKET_CHANGED), result.discardedCandidates().getFirst().reasons());
        assertEquals(CANDIDATE_A_ID, result.discardedCandidates().getFirst().candidateId());
    }

    @Test
    void discardsAllCandidatesWhenGlobalPrerequisitesChanged() {
        BasicOrderCandidate candidateA = buy(CANDIDATE_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate candidateB = buy(CANDIDATE_B_ID, "buy-b", INSTRUMENT_B);

        BasicCandidateRevalidationResult result = new BasicCandidateRevalidator().revalidate(
                converge(candidateA, candidateB),
                evaluated(Map.of(INSTRUMENT_A, "market-a-1", INSTRUMENT_B, "market-b-1")),
                current(
                        Map.of(INSTRUMENT_A, "market-a-1", INSTRUMENT_B, "market-b-1"),
                        Map.of(INSTRUMENT_A, "position-a-1", INSTRUMENT_B, "position-b-1"),
                        Optional.of("budget-2"), OptionalLong.of(8), BasicBotRuntimeStatus.STOPPING));

        assertEquals(List.of(), result.validCandidates());
        assertEquals(List.of(INSTRUMENT_A, INSTRUMENT_B), result.reevaluationInstrumentIds());
        assertEquals(
                List.of(
                        RevalidationReason.BUDGET_CHANGED,
                        RevalidationReason.RUNTIME_STATE_CHANGED,
                        RevalidationReason.BOT_NOT_RUNNING),
                result.discardedCandidates().getFirst().reasons());
    }

    @Test
    void treatsMissingCurrentStateAsInvalidWithoutInventingValues() {
        BasicOrderCandidate candidateA = buy(CANDIDATE_A_ID, "buy-a", INSTRUMENT_A);

        BasicCandidateRevalidationResult result = new BasicCandidateRevalidator().revalidate(
                converge(candidateA),
                evaluated(Map.of(INSTRUMENT_A, "market-a-1")),
                current(Map.of(), Map.of(), Optional.empty(), OptionalLong.empty(), BasicBotRuntimeStatus.RUNNING));

        assertEquals(
                List.of(
                        RevalidationReason.MARKET_STATE_MISSING,
                        RevalidationReason.POSITION_STATE_MISSING,
                        RevalidationReason.BUDGET_STATE_MISSING,
                        RevalidationReason.RUNTIME_STATE_MISSING),
                result.discardedCandidates().getFirst().reasons());
    }

    @Test
    void isIndependentOfCandidateAndMapInsertionOrderAndReturnsImmutableResults() {
        BasicOrderCandidate candidateA = buy(CANDIDATE_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate candidateB = buy(CANDIDATE_B_ID, "buy-b", INSTRUMENT_B);
        Map<UUID, String> reversedMarkets = new LinkedHashMap<>();
        reversedMarkets.put(INSTRUMENT_B, "market-b-1");
        reversedMarkets.put(INSTRUMENT_A, "market-a-1");

        BasicCandidateRevalidator revalidator = new BasicCandidateRevalidator();
        BasicCandidateRevalidationResult forward = revalidator.revalidate(
                converge(candidateA, candidateB),
                evaluated(Map.of(INSTRUMENT_A, "market-a-1", INSTRUMENT_B, "market-b-1")),
                current(reversedMarkets,
                        Map.of(INSTRUMENT_B, "position-b-1", INSTRUMENT_A, "position-a-1"),
                        Optional.of("budget-1"), OptionalLong.of(7), BasicBotRuntimeStatus.RUNNING));
        BasicCandidateRevalidationResult reversed = revalidator.revalidate(
                converge(candidateB, candidateA),
                evaluated(reversedMarkets),
                current(Map.of(INSTRUMENT_A, "market-a-1", INSTRUMENT_B, "market-b-1"),
                        Map.of(INSTRUMENT_A, "position-a-1", INSTRUMENT_B, "position-b-1"),
                        Optional.of("budget-1"), OptionalLong.of(7), BasicBotRuntimeStatus.RUNNING));

        assertEquals(forward, reversed);
        assertEquals(List.of(candidateA, candidateB), forward.validCandidates());
        assertEquals(List.of(), forward.discardedCandidates());
        assertEquals(List.of(), forward.reevaluationInstrumentIds());
        assertThrows(UnsupportedOperationException.class, () -> forward.validCandidates().add(candidateA));
    }

    private static BasicCandidateConvergenceResult converge(BasicOrderCandidate... candidates) {
        return new BasicCandidateConverger().converge(EVALUATION_ID, List.of(candidates));
    }

    private static BasicEvaluationSnapshot evaluated(Map<UUID, String> marketVersions) {
        return new BasicEvaluationSnapshot(
                EVALUATION_ID,
                marketVersions,
                Map.of(INSTRUMENT_A, "position-a-1", INSTRUMENT_B, "position-b-1"),
                "budget-1",
                7);
    }

    private static BasicCurrentSnapshot current(
            Map<UUID, String> marketVersions,
            Map<UUID, String> positionVersions,
            Optional<String> budgetVersion,
            OptionalLong runtimeStateVersion,
            BasicBotRuntimeStatus runtimeStatus) {
        return new BasicCurrentSnapshot(
                marketVersions, positionVersions, budgetVersion, runtimeStateVersion, runtimeStatus);
    }

    private static BasicOrderCandidate buy(UUID candidateId, String flowId, UUID instrumentId) {
        return new BasicOrderCandidate(
                candidateId,
                flowId,
                instrumentId,
                BasicOrderSide.BUY,
                Optional.of(new EqualAllocationShare(1, 1)),
                Map.of("orderType", "MARKET"));
    }
}
