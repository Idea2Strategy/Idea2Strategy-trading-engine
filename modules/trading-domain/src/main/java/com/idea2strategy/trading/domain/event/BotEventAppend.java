package com.idea2strategy.trading.domain.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request to append one row to {@code bot.bot_events}.
 *
 * <p>{@code (bot_id, idempotency_key)} is unique, and the canonical note says that pair is what
 * absorbs at-least-once redelivery without a separate global trigger table. So the key must be
 * derived from the work being recorded, never from a clock or a random value; the same work
 * presented twice has to produce the same key.
 *
 * <p>{@code event_sequence} is deliberately not part of this record. It is allocated by the store
 * under the bot's lock, because the canonical note calls it a runtime audit order that permits gaps
 * rather than a version the caller can predict.
 */
public record BotEventAppend(
        UUID botId,
        BotEventType type,
        String idempotencyKey,
        UUID correlationId,
        UUID causationEventId,
        Instant occurredAt,
        Instant receivedAt,
        String summaryDocument) {

    /** The canonical column is {@code varchar(160)}. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;

    /** The schema version this service writes into {@code event_schema_version}. */
    public static final String EVENT_SCHEMA_VERSION = "1.0.0";

    /**
     * Reserved prefixes keep this service's keys from colliding with the Trigger Router's, whose
     * documented forms are {@code PRICE:...} and {@code SCHEDULE:...}.
     */
    private static final Pattern RESERVED_PREFIX = Pattern.compile("^(?:PRICE|SCHEDULE):");

    public BotEventAppend {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(correlationId, "correlationId");
        idempotencyKey = requireIdempotencyKey(idempotencyKey);
        occurredAt = truncate(Objects.requireNonNull(occurredAt, "occurredAt"));
        receivedAt = truncate(Objects.requireNonNull(receivedAt, "receivedAt"));
        if (receivedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("receivedAt must not precede occurredAt");
        }
        summaryDocument = requireDocument(summaryDocument);
    }

    /**
     * Builds the append for a piece of trading work.
     *
     * <p>The key is {@code <TYPE>:<subject>}, so it is deterministic for the work and carries this
     * service's own prefix rather than one of the router's.
     */
    public static BotEventAppend of(
            UUID botId,
            BotEventType type,
            String subject,
            UUID correlationId,
            Instant occurredAt,
            String summaryDocument) {
        return new BotEventAppend(
                botId,
                type,
                idempotencyKey(type, subject),
                correlationId,
                null,
                occurredAt,
                occurredAt,
                summaryDocument);
    }

    /** Same as {@link #of} but chained to the trigger event that caused this work. */
    public BotEventAppend causedBy(UUID triggerEventId) {
        return new BotEventAppend(
                botId, type, idempotencyKey, correlationId,
                Objects.requireNonNull(triggerEventId, "triggerEventId"),
                occurredAt, receivedAt, summaryDocument);
    }

    /** The deterministic key for one piece of work. */
    public static String idempotencyKey(BotEventType type, String subject) {
        Objects.requireNonNull(type, "type");
        String normalized = Objects.requireNonNull(subject, "subject").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        return requireIdempotencyKey(type.storedValue() + ":" + normalized);
    }

    private static String requireIdempotencyKey(String value) {
        String normalized = Objects.requireNonNull(value, "idempotencyKey").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not exceed " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        if (RESERVED_PREFIX.matcher(normalized).find()) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not use a routed trigger prefix: " + normalized);
        }
        return normalized;
    }

    private static String requireDocument(String value) {
        String normalized = Objects.requireNonNull(value, "summaryDocument").strip();
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            throw new IllegalArgumentException("summaryDocument must be a JSON object");
        }
        return normalized;
    }

    /** PostgreSQL {@code timestamptz} keeps microseconds. */
    private static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
