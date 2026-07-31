package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1;
import com.idea2strategy.trading.messaging.fixture.v1.FixtureDeliveryProjectionV1.DeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureDeliveryProjectionV1Test {

    @Test
    void returnsStaleForLowerAndEqualAggregateVersionsWithNewEventIds() {
        var projection = new FixtureDeliveryProjectionV1();
        var partialFill = ContractFixturesV1.partialFillEnvelope();

        assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(withVersion(partialFill, "21111111-1111-1111-1111-111111111111", 2)))
            .isEqualTo(DeliveryResult.APPLIED);
        assertThat(projection.accept(withVersion(partialFill, "31111111-1111-1111-1111-111111111111", 1)))
            .isEqualTo(DeliveryResult.STALE);
        assertThat(projection.accept(withVersion(partialFill, "41111111-1111-1111-1111-111111111111", 2)))
            .isEqualTo(DeliveryResult.STALE);
    }

    @Test
    void rejectsFutureAggregateVersionGap() {
        var projection = new FixtureDeliveryProjectionV1();
        var partialFill = ContractFixturesV1.partialFillEnvelope();

        assertThat(projection.accept(partialFill)).isEqualTo(DeliveryResult.APPLIED);

        assertThatThrownBy(() -> projection.accept(
            withVersion(partialFill, "51111111-1111-1111-1111-111111111111", 3)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("aggregate version gap");
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
}
