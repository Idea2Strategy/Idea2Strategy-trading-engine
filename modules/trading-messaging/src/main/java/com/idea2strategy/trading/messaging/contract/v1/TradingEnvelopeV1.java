package com.idea2strategy.trading.messaging.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record TradingEnvelopeV1<T>(
    String schemaVersion,
    String eventType,
    UUID eventId,
    Instant occurredAt,
    String producer,
    UUID correlationId,
    UUID causationId,
    String idempotencyKey,
    UUID aggregateId,
    long aggregateVersion,
    T payload
) {
    public TradingEnvelopeV1 {
        ContractValidationV1.requiredText(schemaVersion, "schemaVersion");
        ContractValidationV1.requiredText(eventType, "eventType");
        ContractValidationV1.required(eventId, "eventId");
        ContractValidationV1.utcInstant(occurredAt, "occurredAt");
        ContractValidationV1.requiredText(producer, "producer");
        ContractValidationV1.required(correlationId, "correlationId");
        ContractValidationV1.required(causationId, "causationId");
        ContractValidationV1.requiredText(idempotencyKey, "idempotencyKey");
        ContractValidationV1.required(aggregateId, "aggregateId");
        ContractValidationV1.positiveVersion(aggregateVersion, "aggregateVersion");
        ContractValidationV1.required(payload, "payload");
        validateKnownPayload(schemaVersion, eventType, occurredAt, aggregateId, payload);
    }

    private static void validateKnownPayload(
        String schemaVersion,
        String eventType,
        Instant occurredAt,
        UUID aggregateId,
        Object payload
    ) {
        if (payload instanceof OrderExecutionContractV1.IntentBatch batch) {
            requireContractRoute(schemaVersion, eventType, "order.intent-batch");
            requireAggregate(aggregateId, batch.batchId());
            for (var intent : batch.intents()) {
                if (intent.timeInForce() == OrderExecutionContractV1.TimeInForce.GTD
                    && !intent.expiresAt().isAfter(occurredAt)) {
                    throw new IllegalArgumentException("GTD expiresAt must be after envelope occurredAt");
                }
            }
        }
    }

    private static void requireContractRoute(String schemaVersion, String eventType, String expectedEventType) {
        if (!"trading.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be trading.v1 for a v1 trading payload");
        }
        if (!expectedEventType.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + expectedEventType);
        }
    }

    private static void requireAggregate(UUID aggregateId, UUID payloadAggregateId) {
        if (!aggregateId.equals(payloadAggregateId)) {
            throw new IllegalArgumentException("aggregateId must match the payload aggregate identity");
        }
    }
}
