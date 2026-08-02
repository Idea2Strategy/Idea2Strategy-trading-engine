package com.idea2strategy.trading.strategy.runtime.control;

import java.util.Objects;
import java.util.UUID;

public record StrategyBotOutboxEnvelope(
        UUID messageId,
        String ownerDomain,
        UUID aggregateId,
        long aggregateSequence,
        String eventType,
        String eventSchemaVersion,
        String idempotencyKey,
        String payloadDocument) {

    public StrategyBotOutboxEnvelope {
        messageId = Objects.requireNonNull(messageId, "messageId");
        ownerDomain = requiredText(ownerDomain, "ownerDomain");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateSequence <= 0) {
            throw new IllegalArgumentException("aggregateSequence must be positive");
        }
        eventType = requiredText(eventType, "eventType");
        eventSchemaVersion = requiredText(eventSchemaVersion, "eventSchemaVersion");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey");
        payloadDocument = requiredText(payloadDocument, "payloadDocument");
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
