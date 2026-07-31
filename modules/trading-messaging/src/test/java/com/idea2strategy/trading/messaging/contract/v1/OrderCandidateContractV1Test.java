package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.ACCEPTED;
import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.REDUCED;
import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCandidateContractV1Test {

    @Test
    void intentFixturesRetainCandidateIdentityAndAllDecisionOutcomes() {
        var candidates = ContractFixturesV1.candidateBatchEnvelope().payload();
        var intents = ContractFixturesV1.intentBatchEnvelope().payload();

        assertThat(intents.intents()).extracting(OrderExecutionContractV1.Intent::decision)
            .containsExactly(ACCEPTED, REDUCED, REJECTED);
        assertThat(intents.intents()).extracting(OrderExecutionContractV1.Intent::candidateId)
            .allMatch(id -> candidates.candidates().stream().anyMatch(candidate -> candidate.candidateId().equals(id)));
    }

    @Test
    void candidateBatchDefensivelyCopiesCandidates() {
        var candidate = candidate("11111111-1111-1111-1111-111111111111", "MOMENTUM_SIGNAL");
        var suppliedCandidates = new ArrayList<>(List.of(candidate));
        var batch = batch("2026-07-31-us-equities", suppliedCandidates);

        suppliedCandidates.clear();

        assertThat(batch.candidates()).containsExactly(candidate);
    }

    @Test
    void candidateBatchRejectsBlankPartitionKey() {
        var candidate = candidate("11111111-1111-1111-1111-111111111111", "MOMENTUM_SIGNAL");

        assertThatThrownBy(() -> batch(" ", List.of(candidate)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKey");
    }

    @Test
    void candidateBatchRejectsDuplicateCandidateIds() {
        var candidate = candidate("11111111-1111-1111-1111-111111111111", "MOMENTUM_SIGNAL");

        assertThatThrownBy(() -> batch("2026-07-31-us-equities", List.of(candidate, candidate)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate candidateId");
    }

    @Test
    void candidateBatchRejectsNullCandidateBeforeCopying() {
        var candidate = candidate("11111111-1111-1111-1111-111111111111", "MOMENTUM_SIGNAL");

        assertThatThrownBy(() -> batch("2026-07-31-us-equities", Arrays.asList(candidate, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidate");
    }

    @Test
    void candidateRejectsBlankReasonCode() {
        assertThatThrownBy(() -> candidate("11111111-1111-1111-1111-111111111111", " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    private OrderCandidateContractV1.CandidateBatch batch(
        String partitionKey,
        List<OrderCandidateContractV1.Candidate> candidates
    ) {
        return new OrderCandidateContractV1.CandidateBatch(
            id("22222222-2222-2222-2222-222222222222"),
            id("33333333-3333-3333-3333-333333333333"),
            id("44444444-4444-4444-4444-444444444444"),
            id("55555555-5555-5555-5555-555555555555"),
            partitionKey,
            candidates
        );
    }

    private OrderCandidateContractV1.Candidate candidate(String candidateId, String reasonCode) {
        return new OrderCandidateContractV1.Candidate(
            id(candidateId),
            id("66666666-6666-6666-6666-666666666666"),
            OrderExecutionContractV1.Side.BUY,
            new DecimalValueV1("0.15"),
            reasonCode
        );
    }

    private UUID id(String value) {
        return UUID.fromString(value);
    }
}
