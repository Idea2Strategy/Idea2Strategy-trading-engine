package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementContractV1Test {

    @Test
    void requestedFailedAndSuccessfulRetryUseOneSequentialSettlementHistory() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();
        var failed = ContractFixturesV1.settlementFailedEnvelope();
        var completed = ContractFixturesV1.settlementCompletedEnvelope();
        var projection = new FixtureDeliveryProjectionV1();

        assertThat(requested.payload().settlementId()).isEqualTo(failed.payload().settlementId()).isEqualTo(completed.payload().settlementId());
        assertThat(requested.aggregateVersion()).isEqualTo(1);
        assertThat(failed.aggregateVersion()).isEqualTo(2);
        assertThat(completed.aggregateVersion()).isEqualTo(3);
        assertThat(failed.payload().reasonCode()).isEqualTo("CLEARING_TIMEOUT");
        assertThat(failed.payload().attempt()).isEqualTo(1);
        assertThat(completed.payload().attempt()).isEqualTo(2);

        assertThat(projection.accept(requested)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
        assertThat(projection.accept(requested)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.DUPLICATE);
        var gapProjection = new FixtureDeliveryProjectionV1();
        assertThat(gapProjection.accept(requested)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
        assertThatThrownBy(() -> gapProjection.accept(completed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sequence gap");
        assertThat(projection.accept(failed)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
        assertThat(projection.accept(completed)).isEqualTo(FixtureDeliveryProjectionV1.DeliveryResult.APPLIED);
    }

    @Test
    void settlementEnvelopeTypeAndAggregateMustMatchPayload() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();

        assertThatThrownBy(() -> new TradingEnvelopeV1<>(
            requested.schemaVersion(), "settlement.completed", requested.eventId(), requested.occurredAt(), requested.producer(),
            requested.correlationId(), requested.causationId(), requested.idempotencyKey(), requested.aggregateId(),
            requested.aggregateVersion(), requested.payload()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");
        assertThatThrownBy(() -> new TradingEnvelopeV1<>(
            requested.schemaVersion(), requested.eventType(), requested.eventId(), requested.occurredAt(), requested.producer(),
            requested.correlationId(), requested.causationId(), requested.idempotencyKey(), UUID.randomUUID(),
            requested.aggregateVersion(), requested.payload()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aggregateId");
    }

    @Test
    void failedSettlementRequiresNonblankReasonCode() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.FAILED, " ", 1, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void nonFailedSettlementDoesNotAllowReasonCode() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, "UNEXPECTED", 1, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reasonCode");
    }

    @Test
    void settlementRequiresPositiveAttempt() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 0, List.of(id("00000000-0000-0000-0000-000000000401"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attempt");
    }

    @Test
    void settlementRejectsNullAffectedOrderId() {
        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 1, java.util.Arrays.asList(id("00000000-0000-0000-0000-000000000401"), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("affectedOrderId");
    }

    @Test
    void settlementRejectsDuplicateAffectedOrderIds() {
        var orderId = id("00000000-0000-0000-0000-000000000401");

        assertThatThrownBy(() -> event(SettlementContractV1.EventType.COMPLETED, null, 1, List.of(orderId, orderId)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate affectedOrderId");
    }

    @Test
    void settlementDefensivelyCopiesAffectedOrderIds() {
        var orderId = id("00000000-0000-0000-0000-000000000401");
        var suppliedOrderIds = new ArrayList<>(List.of(orderId));
        var event = event(SettlementContractV1.EventType.COMPLETED, null, 1, suppliedOrderIds);

        suppliedOrderIds.clear();

        assertThat(event.affectedOrderIds()).containsExactly(orderId);
    }

    private SettlementContractV1.Event event(
        SettlementContractV1.EventType type,
        String reasonCode,
        int attempt,
        List<UUID> affectedOrderIds
    ) {
        return new SettlementContractV1.Event(
            id("00000000-0000-0000-0000-000000000701"),
            id("00000000-0000-0000-0000-000000000101"),
            type,
            reasonCode,
            attempt,
            affectedOrderIds
        );
    }

    private UUID id(String value) {
        return UUID.fromString(value);
    }
}
