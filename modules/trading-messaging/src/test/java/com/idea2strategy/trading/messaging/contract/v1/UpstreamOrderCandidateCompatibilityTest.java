package com.idea2strategy.trading.messaging.contract.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import org.junit.jupiter.api.Test;

import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.ACCEPTED;
import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.REDUCED;
import static com.idea2strategy.trading.messaging.contract.v1.OrderExecutionContractV1.IntentDecision.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

class UpstreamOrderCandidateCompatibilityTest {

    @Test
    void loadsTheUpstreamCandidateFixtureAsTheProducerOwnedType() {
        var batch = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/order-candidate-batch.json",
            new TypeReference<OrderCandidateBatch>() {}
        );

        assertThat(batch.schemaVersion()).isEqualTo(1);
        assertThat(batch.evaluationId()).hasToString("626825b7-9de7-447a-a775-d8840fd24e55");
        assertThat(batch.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.candidateId()).hasToString("35c2d356-b7a3-43b4-80c3-fd7cad84cdcd");
            assertThat(candidate.side()).isEqualTo(OrderSide.BUY);
            assertThat(candidate.quantity()).hasToString("2");
            assertThat(candidate.limitPrice()).hasToString("210.12");
        });

        var intents = ContractFixturesV1.intentBatchFor(batch).payload();
        assertThat(intents.evaluationId()).isEqualTo(batch.evaluationId());
        assertThat(intents.intents()).singleElement().satisfies(intent -> {
            assertThat(intent.candidateId()).isEqualTo(batch.candidates().getFirst().candidateId());
            assertThat(intent.instrumentId()).isEqualTo(batch.candidates().getFirst().instrumentId());
            assertThat(intent.side().name()).isEqualTo(batch.candidates().getFirst().side().name());
        });
    }

    @Test
    void intentFixturesRetainProducerCandidateAndEvaluationIdentity() {
        var candidates = ContractFixturesV1.candidateBatch();
        var intents = ContractFixturesV1.intentBatchEnvelope().payload();

        assertThat(intents.evaluationId()).isEqualTo(candidates.evaluationId());
        assertThat(intents.intents()).extracting(OrderExecutionContractV1.Intent::decision)
            .contains(ACCEPTED, REDUCED, REJECTED);
        assertThat(intents.intents()).allSatisfy(intent -> {
            var candidate = candidates.candidates().stream()
                .filter(value -> value.candidateId().equals(intent.candidateId()))
                .findFirst()
                .orElseThrow();
            assertThat(intent.instrumentId()).isEqualTo(candidate.instrumentId());
            assertThat(intent.side().name()).isEqualTo(candidate.side().name());
        });
    }
}
