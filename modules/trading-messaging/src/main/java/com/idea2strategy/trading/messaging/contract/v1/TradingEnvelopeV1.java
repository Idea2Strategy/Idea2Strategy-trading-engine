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
    }
}
