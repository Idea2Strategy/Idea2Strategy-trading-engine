package com.idea2strategy.trading.strategy.runtime.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.strategy.runtime.basic.BasicOrderSide;
import com.idea2strategy.trading.strategy.runtime.basic.EqualAllocationShare;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicCandidateConvergerTest {
    private static final UUID EVALUATION_ID = UUID.fromString("4f7314fa-2af0-421f-93b7-fbc61de94a71");
    private static final UUID INSTRUMENT_A = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final UUID INSTRUMENT_B = UUID.fromString("00000000-0000-4000-8000-000000000302");
    private static final UUID BUY_A_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SELL_A_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BUY_B_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");

    @Test
    void rejectsOppositeSidesForOneInstrumentWithoutAffectingAnotherInstrument() {
        BasicOrderCandidate buyA = buy(BUY_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate sellA = sell(SELL_A_ID, "sell-a", INSTRUMENT_A);
        BasicOrderCandidate buyB = buy(BUY_B_ID, "buy-b", INSTRUMENT_B);

        BasicCandidateConvergenceResult result = new BasicCandidateConverger().converge(
                EVALUATION_ID, List.of(sellA, buyB, buyA));

        assertEquals(List.of(buyB), result.acceptedCandidates());
        assertEquals(CandidateResolutionReason.OPPOSING_ACTION_CONFLICT,
                resolution(result, BUY_A_ID).reason());
        assertEquals(CandidateResolutionReason.OPPOSING_ACTION_CONFLICT,
                resolution(result, SELL_A_ID).reason());
        assertEquals(CandidateResolutionStatus.ACCEPTED, resolution(result, BUY_B_ID).status());
        assertEquals(List.of("buy-a", "sell-a"), resolution(result, BUY_A_ID).relatedFlowIds());
    }

    @Test
    void recordsDuplicateCollapseEvenWhenTheCandidateIsUltimatelyConflicted() {
        BasicOrderCandidate buyA = buy(BUY_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate sellA = sell(SELL_A_ID, "sell-a", INSTRUMENT_A);

        BasicCandidateConvergenceResult result = new BasicCandidateConverger().converge(
                EVALUATION_ID, List.of(buyA, sellA, buyA));

        assertEquals(
                List.of(
                        CandidateResolutionReason.EXACT_DUPLICATE_COLLAPSED,
                        CandidateResolutionReason.OPPOSING_ACTION_CONFLICT),
                resolution(result, BUY_A_ID).reasons());
        assertEquals(2, resolution(result, BUY_A_ID).occurrenceCount());
    }

    @Test
    void collapsesOnlyExactCandidateRetransmissions() {
        BasicOrderCandidate candidate = buy(BUY_A_ID, "buy-a", INSTRUMENT_A);

        BasicCandidateConvergenceResult result = new BasicCandidateConverger().converge(
                EVALUATION_ID, List.of(candidate, candidate, candidate));

        assertEquals(List.of(candidate), result.acceptedCandidates());
        assertEquals(CandidateResolutionReason.EXACT_DUPLICATE_COLLAPSED,
                resolution(result, BUY_A_ID).reason());
        assertEquals(3, resolution(result, BUY_A_ID).occurrenceCount());
    }

    @Test
    void rejectsEveryMeaningForAReusedCandidateIdentity() {
        BasicOrderCandidate original = buy(BUY_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate changedMeaning = new BasicOrderCandidate(
                BUY_A_ID,
                "other-flow",
                INSTRUMENT_A,
                BasicOrderSide.BUY,
                Optional.of(new EqualAllocationShare(1, 2)),
                Map.of("orderType", "LIMIT"));
        BasicOrderCandidate unrelated = buy(BUY_B_ID, "buy-b", INSTRUMENT_B);

        BasicCandidateConvergenceResult result = new BasicCandidateConverger().converge(
                EVALUATION_ID, List.of(changedMeaning, unrelated, original));

        assertEquals(List.of(unrelated), result.acceptedCandidates());
        assertEquals(CandidateResolutionStatus.REJECTED, resolution(result, BUY_A_ID).status());
        assertEquals(CandidateResolutionReason.CANDIDATE_IDENTITY_CONFLICT,
                resolution(result, BUY_A_ID).reason());
        assertEquals(2, resolution(result, BUY_A_ID).occurrenceCount());
        assertEquals(List.of("buy-a", "other-flow"), resolution(result, BUY_A_ID).relatedFlowIds());
    }

    @Test
    void preservesDistinctSameSideCandidatesWithoutHiddenMerging() {
        BasicOrderCandidate first = buy(BUY_A_ID, "buy-a", INSTRUMENT_A);
        BasicOrderCandidate second = buy(BUY_B_ID, "buy-b", INSTRUMENT_A);
        BasicCandidateConverger converger = new BasicCandidateConverger();

        BasicCandidateConvergenceResult forward = converger.converge(EVALUATION_ID, List.of(first, second));
        BasicCandidateConvergenceResult reversed = converger.converge(EVALUATION_ID, List.of(second, first));

        assertEquals(List.of(first, second), forward.acceptedCandidates());
        assertEquals(forward, reversed);
        assertEquals(CandidateResolutionReason.ACCEPTED_AS_SUBMITTED,
                resolution(forward, BUY_A_ID).reason());
        assertEquals(CandidateResolutionReason.ACCEPTED_AS_SUBMITTED,
                resolution(forward, BUY_B_ID).reason());
        assertThrows(UnsupportedOperationException.class,
                () -> forward.acceptedCandidates().add(first));
        assertThrows(UnsupportedOperationException.class,
                () -> forward.resolutions().add(resolution(forward, BUY_A_ID)));
    }

    private static BasicCandidateResolution resolution(BasicCandidateConvergenceResult result, UUID candidateId) {
        return result.resolutions().stream()
                .filter(resolution -> resolution.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow();
    }

    private static BasicOrderCandidate buy(UUID candidateId, String flowId, UUID instrumentId) {
        return new BasicOrderCandidate(
                candidateId, flowId, instrumentId, BasicOrderSide.BUY,
                Optional.of(new EqualAllocationShare(1, 1)), Map.of("orderType", "MARKET"));
    }

    private static BasicOrderCandidate sell(UUID candidateId, String flowId, UUID instrumentId) {
        return new BasicOrderCandidate(
                candidateId, flowId, instrumentId, BasicOrderSide.SELL,
                Optional.empty(), Map.of("sizingMode", "FULL_POSITION"));
    }
}
