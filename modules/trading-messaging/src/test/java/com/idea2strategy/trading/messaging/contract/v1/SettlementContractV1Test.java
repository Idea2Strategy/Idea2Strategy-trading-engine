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
    void completedSettlementIsTerminalAndRejectsLaterFailure() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();
        var failed = ContractFixturesV1.settlementFailedEnvelope();
        var completed = ContractFixturesV1.settlementCompletedEnvelope();
        var laterFailure = settlementEnvelope(
            completed, "00000000-0000-0000-0000-000000000705", 4, completed.eventId(),
            new SettlementContractV1.Event(
                completed.payload().settlementId(), completed.payload().botId(), SettlementContractV1.EventType.FAILED,
                "LATE_FAILURE", 2, completed.payload().affectedOrderIds()
            )
        );
        var projection = new FixtureDeliveryProjectionV1();

        projection.accept(requested);
        projection.accept(failed);
        projection.accept(completed);

        assertThatThrownBy(() -> projection.accept(laterFailure))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("terminal");
    }

    @Test
    void successfulRetryMustAdvanceTheAttemptAfterFailure() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();
        var failed = ContractFixturesV1.settlementFailedEnvelope();
        var completed = ContractFixturesV1.settlementCompletedEnvelope();
        var regressedAttempt = settlementEnvelope(
            completed, completed.eventId().toString(), 3, failed.eventId(),
            new SettlementContractV1.Event(
                completed.payload().settlementId(), completed.payload().botId(), SettlementContractV1.EventType.COMPLETED,
                null, 1, completed.payload().affectedOrderIds()
            )
        );
        var projection = new FixtureDeliveryProjectionV1();

        projection.accept(requested);
        projection.accept(failed);

        assertThatThrownBy(() -> projection.accept(regressedAttempt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("attempt");
    }

    @Test
    void settlementHistoryRejectsBotIdentityDrift() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();
        var failed = ContractFixturesV1.settlementFailedEnvelope();
        var botDrift = settlementEnvelope(
            failed, failed.eventId().toString(), 2, requested.eventId(),
            new SettlementContractV1.Event(
                failed.payload().settlementId(), id("00000000-0000-0000-0000-000000000109"),
                SettlementContractV1.EventType.FAILED, failed.payload().reasonCode(), failed.payload().attempt(),
                failed.payload().affectedOrderIds()
            )
        );
        var projection = new FixtureDeliveryProjectionV1();

        projection.accept(requested);

        assertThatThrownBy(() -> projection.accept(botDrift))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("botId");
    }

    @Test
    void settlementHistoryRejectsAffectedOrderSetDrift() {
        var requested = ContractFixturesV1.settlementRequestedEnvelope();
        var failed = ContractFixturesV1.settlementFailedEnvelope();
        var orderSetDrift = settlementEnvelope(
            failed, failed.eventId().toString(), 2, requested.eventId(),
            new SettlementContractV1.Event(
                failed.payload().settlementId(), failed.payload().botId(), SettlementContractV1.EventType.FAILED,
                failed.payload().reasonCode(), failed.payload().attempt(),
                List.of(id("00000000-0000-0000-0000-000000000409"))
            )
        );
        var projection = new FixtureDeliveryProjectionV1();

        projection.accept(requested);

        assertThatThrownBy(() -> projection.accept(orderSetDrift))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("affectedOrderIds");
    }

    @Test
    void settlementHistoryMustStartWithRequestedAttemptOne() {
        var projection = new FixtureDeliveryProjectionV1();
        var completed = ContractFixturesV1.settlementCompletedEnvelope();
        var initialCompleted = settlementEnvelope(
            completed, "00000000-0000-0000-0000-000000000705", 1, completed.causationId(), completed.payload()
        );

        assertThatThrownBy(() -> projection.accept(initialCompleted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REQUESTED");
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

    private TradingEnvelopeV1<SettlementContractV1.Event> settlementEnvelope(
        TradingEnvelopeV1<SettlementContractV1.Event> template,
        String eventId,
        long aggregateVersion,
        UUID causationId,
        SettlementContractV1.Event payload
    ) {
        String eventType = switch (payload.type()) {
            case REQUESTED -> "settlement.requested";
            case FAILED -> "settlement.failed";
            case COMPLETED -> "settlement.completed";
        };
        return new TradingEnvelopeV1<>(
            template.schemaVersion(), eventType, id(eventId), template.occurredAt(), template.producer(),
            template.correlationId(), causationId, "test-" + eventId, template.aggregateId(), aggregateVersion, payload
        );
    }
}
