package com.idea2strategy.trading.messaging.contract.v1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonFixturesV1Test {

    @ParameterizedTest
    @MethodSource("canonicalFixtures")
    void canonicalFixtureRoundTripsWithoutWireShapeDrift(String resource, TypeReference<?> type) {
        assertThat(ContractJsonFixtureLoaderV1.roundTrips(resource, type)).isTrue();
    }

    @Test
    void unknownSettlementEventTypeFailsMapping() {
        assertThatThrownBy(() -> ContractJsonFixtureLoaderV1.mapper().readValue(
            "{\"settlementId\":\"00000000-0000-0000-0000-000000000701\",\"botId\":\"00000000-0000-0000-0000-000000000101\",\"type\":\"UNKNOWN\",\"reasonCode\":null,\"attempt\":1,\"affectedOrderIds\":[\"00000000-0000-0000-0000-000000000401\"]}",
            SettlementContractV1.Event.class
        )).isInstanceOf(JsonMappingException.class);
    }

    @Test
    void ignoresUnknownOptionalPropertiesWhileKeepingUnknownEnumsStrict() throws Exception {
        var settlement = ContractJsonFixtureLoaderV1.mapper().readValue(
            "{\"settlementId\":\"00000000-0000-0000-0000-000000000701\",\"botId\":\"00000000-0000-0000-0000-000000000101\",\"type\":\"COMPLETED\",\"reasonCode\":null,\"attempt\":2,\"affectedOrderIds\":[\"00000000-0000-0000-0000-000000000401\"],\"futureOptionalField\":\"ignored\"}",
            SettlementContractV1.Event.class
        );

        assertThat(settlement.type()).isEqualTo(SettlementContractV1.EventType.COMPLETED);
    }

    @Test
    void canonicalEnvelopeEventIdsAreUnique() {
        var eventIds = ContractJsonFixtureLoaderV1.canonicalEnvelopeEventIds();

        assertThat(eventIds).hasSize(12);
        assertThat(new HashSet<>(eventIds)).hasSameSizeAs(eventIds);
    }

    @Test
    void canonicalIdentityGraphLinksCandidateIntentOrderFillAndLedger() {
        var candidates = ContractJsonFixtureLoaderV1.readResource(
            "contracts/v1/order-candidate-batch.json", new TypeReference<OrderCandidateBatch>() {}
        );
        var intents = read("intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {});
        var accepted = read("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {});
        var partial = read("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {});
        var filled = read("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {});
        var ledger = read("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {});

        var acceptedIntent = intents.payload().intents().stream()
            .filter(intent -> intent.intentId().equals(accepted.payload().intentId()))
            .findFirst()
            .orElseThrow();
        assertThat(accepted.payload().candidateId()).isEqualTo(acceptedIntent.candidateId());
        assertThat(candidates.candidates()).extracting(candidate -> candidate.candidateId())
            .contains(acceptedIntent.candidateId());
        assertThat(intents.causationId()).isEqualTo(candidates.batchId());
        assertThat(accepted.causationId()).isEqualTo(intents.eventId());
        assertThat(partial.causationId()).isEqualTo(accepted.eventId());
        assertThat(filled.causationId()).isEqualTo(partial.eventId());
        assertThat(ledger.causationId()).isEqualTo(partial.eventId());

        var independentlyPostedEntryIds = Stream.of(
                partial.payload().ledgerTransaction(),
                filled.payload().ledgerTransaction()
            ).flatMap(transaction -> transaction.entries().stream())
            .map(LedgerContractV1.Entry::entryId)
            .toList();
        assertThat(new HashSet<>(independentlyPostedEntryIds)).hasSameSizeAs(independentlyPostedEntryIds);
    }

    @Test
    void standaloneLedgerEnvelopeRepublishesTheEmbeddedPartialFillTransaction() {
        var partial = read("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {});
        var ledger = read("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {});

        assertThat(ledger.payload()).isEqualTo(partial.payload().ledgerTransaction());
        assertThat(ledger.payload().transactionId()).hasToString("00000000-0000-0000-0000-000000000601");
        assertThat(ledger.payload().entries()).extracting(LedgerContractV1.Entry::entryId)
            .extracting(UUID::toString)
            .containsExactly(
                "00000000-0000-0000-0000-000000000611",
                "00000000-0000-0000-0000-000000000612"
            );
        assertThat(ledger.causationId()).isEqualTo(partial.eventId());
        assertThat(ledger.payload().sourceEventId()).isEqualTo(ledger.causationId());
        assertThat(ledger.payload().entries()).allSatisfy(entry ->
            assertThat(entry.sourceEventId()).isEqualTo(ledger.causationId())
        );
        assertThat(ledger.payload().sourceEventId()).isNotEqualTo(ledger.eventId());
    }

    @Test
    void canonicalDeliveryScenarioStartsWithAcceptedBeforeDuplicatePartialFill() {
        var scenario = read("delivery-scenario.json", new TypeReference<FixtureDeliveryProjectionV1.DeliveryScenario>() {});
        var accepted = ContractFixturesV1.acceptedEnvelope();
        var partial = ContractFixturesV1.partialFillEnvelope();
        var projection = new FixtureDeliveryProjectionV1();

        assertThat(scenario.deliveryEventIds()).containsExactly(accepted.eventId(), partial.eventId());
        assertThat(projection.accept(accepted)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
        assertThat(projection.accept(partial)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
        assertThat(projection.accept(partial)).isEqualTo(scenario.duplicateResult());
        assertThat(projection.tradeCount()).isEqualTo(scenario.expectedTradeCount());
        assertThat(projection.ledgerEntryCount()).isEqualTo(scenario.expectedLedgerEntryCount());
    }

    @Test
    void canonicalResourcesMatchDeterministicJavaFixtures() {
        assertThat(read("intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}))
            .isEqualTo(ContractFixturesV1.intentBatchEnvelope());
        assertThat(read("intent-decision-scenarios.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}))
            .isEqualTo(ContractFixturesV1.intentDecisionScenarioEnvelope());
        assertThat(read("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.acceptedEnvelope());
        assertThat(read("order-cancel-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.cancellationAcceptedEnvelope());
        assertThat(read("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.partialFillEnvelope());
        assertThat(read("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.filledEnvelope());
        assertThat(read("order-cancelled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.cancelledEnvelope());
        assertThat(read("order-rejected.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.rejectedEnvelope());
        assertThat(read("settlement-requested.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.settlementRequestedEnvelope());
        assertThat(read("settlement-failed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.settlementFailedEnvelope());
        assertThat(read("settlement-completed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.settlementCompletedEnvelope());
        assertThat(read("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {}))
            .isEqualTo(ContractFixturesV1.ledgerTransactionEnvelope());
        assertThat(read("delivery-scenario.json", new TypeReference<FixtureDeliveryProjectionV1.DeliveryScenario>() {}))
            .isEqualTo(ContractFixturesV1.deliveryScenario());
    }

    static Stream<Arguments> canonicalFixtures() {
        return Stream.of(
            Arguments.of("intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}),
            Arguments.of("intent-decision-scenarios.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}),
            Arguments.of("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-cancel-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-cancelled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-rejected.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("settlement-requested.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
            Arguments.of("settlement-failed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
            Arguments.of("settlement-completed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
            Arguments.of("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {}),
            Arguments.of("delivery-scenario.json", new TypeReference<FixtureDeliveryProjectionV1.DeliveryScenario>() {})
        );
    }

    private <T> T read(String resource, TypeReference<T> type) {
        return ContractJsonFixtureLoaderV1.read(resource, type);
    }
}
