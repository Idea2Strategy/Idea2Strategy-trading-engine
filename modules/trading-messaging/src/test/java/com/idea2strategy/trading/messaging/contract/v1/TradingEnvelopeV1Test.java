package com.idea2strategy.trading.messaging.contract.v1;

import com.idea2strategy.trading.messaging.fixture.v1.ContractFixturesV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEnvelopeV1Test {

    @Test
    void validatesSchemaEventTypeAggregateAndGtdTimeAgainstKnownPayload() {
        var canonical = ContractFixturesV1.intentBatchEnvelope();

        assertThatThrownBy(() -> copyKnownEnvelope(canonical, "other.v1", canonical.eventType(), canonical.aggregateId(), canonical.occurredAt(), canonical.payload()))
            .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> copyKnownEnvelope(canonical, canonical.schemaVersion(), "order.accepted", canonical.aggregateId(), canonical.occurredAt(), canonical.payload()))
            .hasMessageContaining("eventType");
        assertThatThrownBy(() -> copyKnownEnvelope(canonical, canonical.schemaVersion(), canonical.eventType(), UUID.fromString("89999999-9999-9999-9999-999999999999"), canonical.occurredAt(), canonical.payload()))
            .hasMessageContaining("aggregateId");

        var gtdIntent = new OrderExecutionContractV1.Intent(
            UUID.fromString("85111111-1111-1111-1111-111111111111"),
            UUID.fromString("85222222-2222-2222-2222-222222222222"),
            UUID.fromString("85333333-3333-3333-3333-333333333333"),
            OrderExecutionContractV1.Side.BUY,
            OrderExecutionContractV1.OrderType.MARKET,
            new OrderExecutionContractV1.OrderParameters(null, null, null),
            OrderExecutionContractV1.TimeInForce.GTD,
            canonical.occurredAt(),
            OrderExecutionContractV1.QuantityMode.WHOLE_SHARES,
            new DecimalValueV1("1"),
            new DecimalValueV1("1"),
            OrderExecutionContractV1.IntentDecision.ACCEPTED,
            null,
            new OrderExecutionContractV1.CostPolicy("cost-policy-v1", new DecimalValueV1("0.002"), new DecimalValueV1("0.0005"))
        );
        var gtdBatch = new OrderExecutionContractV1.IntentBatch(
            canonical.payload().batchId(), canonical.payload().botId(), canonical.payload().evaluationId(), List.of(gtdIntent)
        );

        assertThatThrownBy(() -> copyKnownEnvelope(canonical, canonical.schemaVersion(), canonical.eventType(), canonical.aggregateId(), canonical.occurredAt(), gtdBatch))
            .hasMessageContaining("expiresAt")
            .hasMessageContaining("occurredAt");
    }

    @Test
    void validatesLifecycleRouteAndNestedLedgerIdentityAndTime() {
        var partial = ContractFixturesV1.partialFillEnvelope();

        assertThatThrownBy(() -> new TradingEnvelopeV1<>(
            partial.schemaVersion(), "order.filled", partial.eventId(), partial.occurredAt(), partial.producer(),
            partial.correlationId(), partial.causationId(), partial.idempotencyKey(), partial.aggregateId(),
            partial.aggregateVersion(), partial.payload()
        )).hasMessageContaining("eventType");
        assertThatThrownBy(() -> new TradingEnvelopeV1<>(
            partial.schemaVersion(), partial.eventType(), UUID.fromString("87777777-7777-7777-7777-777777777777"),
            partial.occurredAt(), partial.producer(), partial.correlationId(), partial.causationId(), partial.idempotencyKey(),
            partial.aggregateId(), partial.aggregateVersion(), partial.payload()
        )).hasMessageContaining("sourceEventId");

        var transaction = partial.payload().ledgerTransaction();
        var lateTransaction = new LedgerContractV1.Transaction(
            transaction.transactionId(), transaction.sourceEventId(), partial.occurredAt().plusSeconds(1), transaction.entries()
        );
        var latePosting = new OrderLifecycleContractV1.Event(
            partial.payload().orderId(), partial.payload().intentId(), partial.payload().candidateId(), partial.payload().type(),
            partial.payload().orderQuantity(), partial.payload().fillQuantity(), partial.payload().fillPrice(), lateTransaction, null
        );
        assertThatThrownBy(() -> new TradingEnvelopeV1<>(
            partial.schemaVersion(), partial.eventType(), partial.eventId(), partial.occurredAt(), partial.producer(),
            partial.correlationId(), partial.causationId(), partial.idempotencyKey(), partial.aggregateId(),
            partial.aggregateVersion(), latePosting
        )).hasMessageContaining("postedAt");
    }

    @Test
    void rejectsInvalidRequiredMetadataAndNoncanonicalDecimals() {
        assertThatThrownBy(() -> envelopeWithSchemaVersion(" "))
            .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> envelopeWithAggregateVersion(0))
            .hasMessageContaining("aggregateVersion");
        assertThatThrownBy(() -> envelopeWithPayload(null))
            .hasMessageContaining("payload");
        assertThatThrownBy(() -> new DecimalValueV1("1e3"))
            .hasMessageContaining("decimal");
        assertThatThrownBy(() -> new DecimalValueV1("1.20"))
            .hasMessageContaining("canonical");
    }

    private TradingEnvelopeV1<String> envelopeWithSchemaVersion(String schemaVersion) {
        return envelope(schemaVersion, 1, "payload");
    }

    private TradingEnvelopeV1<String> envelopeWithAggregateVersion(long aggregateVersion) {
        return envelope("trading-envelope.v1", aggregateVersion, "payload");
    }

    private TradingEnvelopeV1<String> envelopeWithPayload(String payload) {
        return envelope("trading-envelope.v1", 1, payload);
    }

    private TradingEnvelopeV1<String> envelope(String schemaVersion, long aggregateVersion, String payload) {
        return new TradingEnvelopeV1<>(
            schemaVersion,
            "order.partially-filled",
            UUID.fromString("81111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-31T00:00:00Z"),
            "trading-engine",
            UUID.fromString("82222222-2222-2222-2222-222222222222"),
            UUID.fromString("83333333-3333-3333-3333-333333333333"),
            "partial-fill-1",
            UUID.fromString("84444444-4444-4444-4444-444444444444"),
            aggregateVersion,
            payload
        );
    }

    private TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> copyKnownEnvelope(
        TradingEnvelopeV1<OrderExecutionContractV1.IntentBatch> original,
        String schemaVersion,
        String eventType,
        UUID aggregateId,
        Instant occurredAt,
        OrderExecutionContractV1.IntentBatch payload
    ) {
        return new TradingEnvelopeV1<>(
            schemaVersion, eventType, original.eventId(), occurredAt, original.producer(), original.correlationId(),
            original.causationId(), original.idempotencyKey(), aggregateId, original.aggregateVersion(), payload
        );
    }
}
