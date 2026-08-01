package com.idea2strategy.trading.messaging.contract.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import com.idea2strategy.trading.messaging.evaluation.EvaluationResult;
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

        var evaluation = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/evaluation-result.json",
            new TypeReference<EvaluationResult>() {}
        );
        var intents = ContractFixturesV1.intentBatchFor(batch, evaluation).payload();
        assertThat(intents.evaluationId()).isEqualTo(batch.evaluationId());
        assertThat(intents.botId()).isEqualTo(evaluation.botId());
        assertThat(intents.intents()).singleElement().satisfies(intent -> {
            assertThat(intent.candidateId()).isEqualTo(batch.candidates().getFirst().candidateId());
            assertThat(intent.instrumentId()).isEqualTo(batch.candidates().getFirst().instrumentId());
            assertThat(intent.side().name()).isEqualTo(batch.candidates().getFirst().side().name());
        });
    }

    @Test
    void committedIntentResourceIsDerivedFromBothUpstreamResources() {
        var candidates = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/order-candidate-batch.json", new TypeReference<OrderCandidateBatch>() {}
        );
        var evaluation = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/evaluation-result.json", new TypeReference<EvaluationResult>() {}
        );
        var committed = ContractJsonFixtureLoaderV1.read(
            "intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}
        );

        assertThat(committed).isEqualTo(ContractFixturesV1.intentBatchFor(candidates, evaluation));
        assertThat(committed.payload().evaluationId()).hasToString("626825b7-9de7-447a-a775-d8840fd24e55");
        assertThat(committed.payload().botId()).hasToString("e332fd66-3a21-4d3e-8a2a-4c2e4ee55430");
        assertThat(committed.payload().intents()).singleElement().satisfies(intent -> {
            assertThat(intent.candidateId()).hasToString("35c2d356-b7a3-43b4-80c3-fd7cad84cdcd");
            assertThat(intent.instrumentId()).hasToString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");
            assertThat(intent.side()).isEqualTo(OrderExecutionContractV1.Side.BUY);
            assertThat(intent.requestedQuantity().value()).isEqualTo("2");
            assertThat(intent.orderType()).isEqualTo(OrderExecutionContractV1.OrderType.LIMIT);
            assertThat(intent.orderParameters().limitPrice().amount().value()).isEqualTo("210.12");
        });
    }

    @Test
    void intentFixturesRetainProducerCandidateAndEvaluationIdentity() {
        var candidates = ContractFixturesV1.decisionScenarioCandidateBatch();
        var intents = ContractFixturesV1.intentDecisionScenarioEnvelope().payload();

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
