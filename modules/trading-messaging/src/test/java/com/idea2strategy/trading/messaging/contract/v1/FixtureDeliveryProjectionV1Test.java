package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1.DeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureDeliveryProjectionV1Test {

    @Test
    void returnsStaleForLowerAndEqualAggregateVersionsWithNewEventIds() {
        var projection = new FixtureDeliveryProjectionV1();
        var accepted = ContractFixturesV1.acceptedEnvelope();
        var partialFill = ContractFixturesV1.partialFillEnvelope();

        assertThat(projection.accept(accepted)).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(withVersion(accepted, "31111111-1111-1111-1111-111111111111", 1)))
            .isEqualTo(DeliveryResult.STALE);
        assertThat(projection.accept(withVersion(accepted, "41111111-1111-1111-1111-111111111111", 2)))
            .isEqualTo(DeliveryResult.STALE);
    }

    @Test
    void rejectsFutureAggregateVersionGap() {
        var projection = new FixtureDeliveryProjectionV1();
        var accepted = ContractFixturesV1.acceptedEnvelope();

        assertThat(projection.accept(accepted)).isEqualTo(DeliveryResult.APPLIED);

        assertThatThrownBy(() -> projection.accept(
            withVersion(accepted, "51111111-1111-1111-1111-111111111111", 3)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("aggregate version gap");
    }

    @Test
    void appliesAcceptedPartialFilledHistoryAndRejectsPostTerminalTransition() {
        var projection = new FixtureDeliveryProjectionV1();

        assertThat(projection.accept(ContractFixturesV1.acceptedEnvelope())).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(ContractFixturesV1.partialFillEnvelope())).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(ContractFixturesV1.filledEnvelope())).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.tradeCount()).isEqualTo(2);

        var filled = ContractFixturesV1.filledEnvelope();
        var cancelled = new OrderLifecycleContractV1.Event(
            filled.payload().orderId(), filled.payload().intentId(), filled.payload().candidateId(),
            OrderLifecycleContractV1.EventType.CANCELLED, filled.payload().orderQuantity(),
            null, null, null, "LATE_CANCEL"
        );
        assertThatThrownBy(() -> projection.accept(withPayloadAndVersion(
            filled, "61111111-1111-1111-1111-111111111111", 4, cancelled
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("terminal");
    }

    @Test
    void rejectsFillThatExceedsTheAcceptedOrderQuantity() {
        var projection = new FixtureDeliveryProjectionV1();
        var partial = ContractFixturesV1.partialFillEnvelope();
        var overfill = new OrderLifecycleContractV1.Event(
            partial.payload().orderId(), partial.payload().intentId(), partial.payload().candidateId(),
            OrderLifecycleContractV1.EventType.PARTIALLY_FILLED, partial.payload().orderQuantity(),
            new DecimalValueV1("11"), partial.payload().fillPrice(), partial.payload().ledgerTransaction(), null
        );

        assertThat(projection.accept(ContractFixturesV1.acceptedEnvelope())).isEqualTo(DeliveryResult.APPLIED);
        assertThatThrownBy(() -> projection.accept(withPayloadAndVersion(
            partial, partial.eventId().toString(), 2, overfill
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("overfill");
    }

    @Test
    void cancellationAndRejectionUseIndependentSequentialHistories() {
        var cancellationProjection = new FixtureDeliveryProjectionV1();
        assertThat(cancellationProjection.accept(ContractFixturesV1.cancellationAcceptedEnvelope()))
            .isEqualTo(DeliveryResult.APPLIED);
        assertThat(cancellationProjection.accept(ContractFixturesV1.cancelledEnvelope()))
            .isEqualTo(DeliveryResult.APPLIED);

        var rejectionProjection = new FixtureDeliveryProjectionV1();
        assertThat(rejectionProjection.accept(ContractFixturesV1.rejectedEnvelope()))
            .isEqualTo(DeliveryResult.APPLIED);
    }

    @Test
    void deliveryScenarioRejectsNegativeNullAndDuplicateValues() {
        var eventId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        assertThatThrownBy(() -> scenario(List.of(eventId), -1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expectedTradeCount");
        assertThatThrownBy(() -> scenario(Arrays.asList(eventId, null), 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deliveryEventId");
        assertThatThrownBy(() -> scenario(List.of(eventId, eventId), 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate deliveryEventId");
    }

    private TradingEnvelopeV1<?> withVersion(
        TradingEnvelopeV1<?> original,
        String eventId,
        long aggregateVersion
    ) {
        return new TradingEnvelopeV1<>(
            original.schemaVersion(),
            original.eventType(),
            UUID.fromString(eventId),
            original.occurredAt(),
            original.producer(),
            original.correlationId(),
            original.causationId(),
            original.idempotencyKey(),
            original.aggregateId(),
            aggregateVersion,
            original.payload()
        );
    }

    private FixtureDeliveryProjectionV1.DeliveryScenario scenario(
        List<UUID> eventIds,
        int expectedTradeCount,
        int expectedLedgerEntryCount
    ) {
        return new FixtureDeliveryProjectionV1.DeliveryScenario(
            eventIds, expectedTradeCount, expectedLedgerEntryCount,
            DeliveryResult.DUPLICATE, DeliveryResult.STALE, "aggregate version gap: sequence gap"
        );
    }

    private TradingEnvelopeV1<?> withPayloadAndVersion(
        TradingEnvelopeV1<?> original,
        String eventId,
        long aggregateVersion,
        OrderLifecycleContractV1.Event payload
    ) {
        return new TradingEnvelopeV1<>(
            original.schemaVersion(), "order." + switch (payload.type()) {
                case PARTIALLY_FILLED -> "partially-filled";
                case FILLED -> "filled";
                case CANCELLED -> "cancelled";
                case ACCEPTED -> "accepted";
                case EXPIRED -> "expired";
                case REJECTED -> "rejected";
            }, UUID.fromString(eventId), original.occurredAt(), original.producer(), original.correlationId(),
            original.causationId(), "test-" + eventId, original.aggregateId(), aggregateVersion, payload
        );
    }
}
