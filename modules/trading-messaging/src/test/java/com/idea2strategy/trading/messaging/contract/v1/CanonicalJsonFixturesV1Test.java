package com.idea2strategy.trading.messaging.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
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
        )).isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void canonicalEnvelopeEventIdsAreUnique() {
        var eventIds = ContractJsonFixtureLoaderV1.canonicalEnvelopeEventIds();

        assertThat(eventIds).hasSize(8);
        assertThat(new HashSet<>(eventIds)).hasSameSizeAs(eventIds);
    }

    @Test
    void canonicalResourcesMatchDeterministicJavaFixtures() {
        assertThat(read("intent-batch.json", new TypeReference<TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch>>() {}))
            .isEqualTo(ContractFixturesV1.intentBatchEnvelope());
        assertThat(read("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.acceptedEnvelope());
        assertThat(read("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.partialFillEnvelope());
        assertThat(read("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.filledEnvelope());
        assertThat(read("order-cancelled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.cancelledEnvelope());
        assertThat(read("order-rejected.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}))
            .isEqualTo(ContractFixturesV1.rejectedEnvelope());
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
            Arguments.of("order-accepted.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-partial-fill.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-filled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-cancelled.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("order-rejected.json", new TypeReference<TradingEnvelopeV1<OrderLifecycleContractV1.Event>>() {}),
            Arguments.of("settlement-completed.json", new TypeReference<TradingEnvelopeV1<SettlementContractV1.Event>>() {}),
            Arguments.of("ledger-transaction.json", new TypeReference<TradingEnvelopeV1<LedgerContractV1.Transaction>>() {}),
            Arguments.of("delivery-scenario.json", new TypeReference<FixtureDeliveryProjectionV1.DeliveryScenario>() {})
        );
    }

    private <T> T read(String resource, TypeReference<T> type) {
        return ContractJsonFixtureLoaderV1.read(resource, type);
    }
}
