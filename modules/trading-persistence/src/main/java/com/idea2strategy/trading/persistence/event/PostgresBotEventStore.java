package com.idea2strategy.trading.persistence.event;

import com.idea2strategy.trading.application.event.BotEventConflictException;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends to the canonical {@code bot.bot_events} stream.
 *
 * <p>Two canonical uniqueness rules shape this. {@code (bot_id, idempotency_key)} is what absorbs
 * at-least-once redelivery, so a repeat of identical work returns the stored row instead of adding
 * a second one. {@code (bot_id, event_sequence)} is unique, so the sequence has to be allocated
 * under a per-bot lock; the canonical note calls the sequence a runtime audit order that tolerates
 * gaps, which is why a rolled back attempt simply leaves one.
 */
@Repository
public class PostgresBotEventStore implements BotEventStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresBotEventStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public BotEvent appendOrLoad(BotEventAppend append) {
        Objects.requireNonNull(append, "append");
        return transaction.execute(status -> appendInTransaction(append));
    }

    @Override
    public Optional<BotEvent> find(UUID botId, UUID eventId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(eventId, "eventId");
        return jdbc.sql(SELECT_BY_ID)
                .param(botId)
                .param(eventId)
                .query(PostgresBotEventStore::toEvent)
                .optional();
    }

    private BotEvent appendInTransaction(BotEventAppend append) {
        Optional<BotEvent> existing = loadByKey(append);
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), append);
        }

        // Serialise sequence allocation for this bot. The lock is transaction scoped, so a
        // concurrent append for the same bot waits here and then reads the sequence this one used.
        // pg_advisory_xact_lock returns void, so the row is wrapped to give the driver a value.
        jdbc.sql("select true as locked from (select pg_advisory_xact_lock(hashtextextended(?, 0))) held")
                .param(append.botId().toString())
                .query(Boolean.class)
                .single();

        existing = loadByKey(append);
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), append);
        }

        UUID eventId = UUID.randomUUID();
        long sequence = nextSequence(append.botId());
        try {
            jdbc.sql(INSERT)
                    .param(eventId)
                    .param(append.botId())
                    .param(sequence)
                    .param(append.type().storedValue())
                    .param(BotEventAppend.EVENT_SCHEMA_VERSION)
                    .param(append.causationEventId())
                    .param(append.correlationId())
                    .param(append.idempotencyKey())
                    .param(offset(append.occurredAt()))
                    .param(offset(append.receivedAt()))
                    .param(append.summaryDocument())
                    .update();
        } catch (DataIntegrityViolationException violation) {
            throw new BotEventConflictException(
                    "unable to append bot event " + append.idempotencyKey(), violation);
        }

        return find(append.botId(), eventId)
                .orElseThrow(() -> new BotEventConflictException("appended bot event was not stored"));
    }

    private static BotEvent replayOrConflict(BotEvent stored, BotEventAppend append) {
        if (!stored.records(append)) {
            throw new BotEventConflictException(
                    "bot event idempotency key " + append.idempotencyKey()
                            + " already records different work");
        }
        return stored;
    }

    private Optional<BotEvent> loadByKey(BotEventAppend append) {
        return jdbc.sql(SELECT_BY_KEY)
                .param(append.botId())
                .param(append.idempotencyKey())
                .query(PostgresBotEventStore::toEvent)
                .optional();
    }

    private long nextSequence(UUID botId) {
        Long current = jdbc.sql(
                        "select max(event_sequence) from bot.bot_events where bot_id = ?")
                .param(botId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return current == null ? 1L : current + 1L;
    }

    private static OffsetDateTime offset(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static BotEvent toEvent(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new BotEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getLong("event_sequence"),
                BotEventType.valueOf(rs.getString("event_type")),
                rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_event_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getString("summary_document"));
    }

    private static final String COLUMNS = """
            id, bot_id, event_sequence, event_type, event_schema_version, causation_event_id,
            correlation_id, idempotency_key, occurred_at, received_at, summary_document
            """;

    private static final String SELECT_BY_ID =
            "select " + COLUMNS + " from bot.bot_events where bot_id = ? and id = ?";

    private static final String SELECT_BY_KEY =
            "select " + COLUMNS + " from bot.bot_events where bot_id = ? and idempotency_key = ?";

    private static final String INSERT = """
            insert into bot.bot_events (
                id, bot_id, event_sequence, event_type, event_schema_version, causation_event_id,
                correlation_id, idempotency_key, occurred_at, received_at, summary_document)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;
}
