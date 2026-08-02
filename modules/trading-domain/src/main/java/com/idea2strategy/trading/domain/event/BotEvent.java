package com.idea2strategy.trading.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A row that exists in {@code bot.bot_events}. */
public record BotEvent(
        UUID eventId,
        UUID botId,
        long eventSequence,
        BotEventType type,
        String idempotencyKey,
        UUID correlationId,
        UUID causationEventId,
        Instant occurredAt,
        Instant receivedAt,
        String summaryDocument) {

    public BotEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(botId, "botId");
        if (eventSequence < 1) {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(summaryDocument, "summaryDocument");
    }

    /** True when this row already recorded the same work as the request. */
    public boolean records(BotEventAppend append) {
        Objects.requireNonNull(append, "append");
        return botId.equals(append.botId())
                && type == append.type()
                && idempotencyKey.equals(append.idempotencyKey())
                && correlationId.equals(append.correlationId())
                && Objects.equals(causationEventId, append.causationEventId())
                && occurredAt.equals(append.occurredAt())
                && receivedAt.equals(append.receivedAt());
    }
}
