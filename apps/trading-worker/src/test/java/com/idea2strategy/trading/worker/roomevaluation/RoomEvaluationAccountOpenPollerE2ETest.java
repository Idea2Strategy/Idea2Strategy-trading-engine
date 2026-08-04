package com.idea2strategy.trading.worker.roomevaluation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.event.PostgresBotEventStore;
import com.idea2strategy.trading.persistence.ledger.PostgresLedgerStore;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Root #181's durable, atomic E-to-F account-opening acceptance path. */
@Testcontainers(disabledWithoutDocker = true)
class RoomEvaluationAccountOpenPollerE2ETest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("f1810000-0000-4000-8000-000000000101");
    private static final UUID ROOM = UUID.fromString("f1810000-0000-4000-8000-000000000102");
    private static final UUID PARTICIPATION = UUID.fromString("f1810000-0000-4000-8000-000000000103");
    private static final UUID SEGMENT = UUID.fromString("f1810000-0000-4000-8000-000000000104");
    private static final UUID COMMAND = UUID.fromString("f1810000-0000-4000-8000-000000000105");
    private static final UUID FEE_POLICY = UUID.fromString("f1810000-0000-4000-8000-000000000106");
    private static final UUID BUYING_POWER_POLICY = UUID.fromString("f1810000-0000-4000-8000-000000000107");
    private static final Instant NOW = Instant.parse("2026-08-04T15:00:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcTransactionManager transactions;
    private static JdbcClient jdbc;

    @BeforeAll
    static void database() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        jdbc = JdbcClient.create(dataSource);
        // Backend's preceding root #181 envelope migration; remove this compatibility step when
        // the next pinned canonical baseline includes V20260804145900.
        jdbc.sql("alter table operations.outbox_messages alter column event_schema_version type varchar(80)").update();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.execute("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'f1810000-0000-4000-8000-000000000199', 'BASIC', 'Room bot',
                        'RUNNING', '%s', '%s', '%s')
                    """.formatted(BOT, NOW, NOW, NOW));
            statement.execute("set session_replication_role = origin");
        }
    }

    @BeforeEach
    void clear() {
        jdbc.sql("delete from operations.outbox_consumer_receipts").update();
        jdbc.sql("delete from operations.audit_events where target_domain = 'trading'").update();
        jdbc.sql("delete from operations.outbox_messages where owner_domain in ('room-performance', 'trading')").update();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("delete from trading.ledger_entries").update();
            jdbc.sql("delete from trading.ledger_transactions").update();
            jdbc.sql("delete from trading.ledger_accounts").update();
        });
        jdbc.sql("delete from bot.bot_events where bot_id = :bot").param("bot", BOT).update();
    }

    @Test
    void opensExactlyOneBalancedLedgerAndPublishesOneCompletionAcrossRedelivery() {
        seedRequest(message(1), "room-open:" + PARTICIPATION, "100000.00000000");
        RoomEvaluationAccountOpenPoller poller = poller(new PostgresLedgerStore(jdbc, transactions));

        assertEquals(1, poller.pollOnce(10));
        assertEquals(0, poller.pollOnce(10));

        assertAll(
                () -> assertEquals(1, count("bot.bot_events", "event_type = 'INITIAL_CAPITAL_POSTED'")),
                () -> assertEquals(2, count("trading.ledger_accounts", "account_type in ('CASH', 'CAPITAL')")),
                () -> assertEquals(1, count("trading.ledger_transactions", "transaction_type = 'LEDGER_POSTING'")),
                () -> assertEquals(2, count("trading.ledger_entries", "amount = 100000.00000000")),
                () -> assertEquals(1, count("operations.outbox_consumer_receipts", "status = 'COMPLETED'")),
                () -> assertEquals(1, count("operations.outbox_messages", "event_type = 'ROOM_EVALUATION_ACCOUNT_OPENED'")));
    }

    @Test
    void producerKeyReusedWithDifferentContentIsPermanentAndAuditedWithoutAnotherLedger() {
        String producerKey = "room-open:" + PARTICIPATION;
        seedRequest(message(1), producerKey, "100000.00000000");
        RoomEvaluationAccountOpenPoller poller = poller(new PostgresLedgerStore(jdbc, transactions));
        assertEquals(1, poller.pollOnce(10));

        seedRequest(message(2), producerKey, "99999.00000000");
        assertEquals(0, poller.pollOnce(10));

        assertAll(
                () -> assertEquals(1, count("trading.ledger_transactions", "true")),
                () -> assertEquals(2, count("trading.ledger_entries", "true")),
                () -> assertEquals(1, count("operations.outbox_consumer_receipts", "status = 'PERMANENT_FAILURE'")),
                () -> assertEquals(1, count("operations.audit_events", "reason_code = 'PRODUCER_KEY_CONTENT_CONFLICT'")),
                () -> assertEquals(1, count("operations.outbox_messages", "event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED'")));
    }

    @Test
    void failureAfterBotEventAppendRollsBackEveryBusinessEffectAndLeavesRetryEvidence() {
        seedRequest(message(1), "room-open:" + PARTICIPATION, "100000.00000000");
        LedgerStore failure = command -> { throw new IllegalStateException("simulated ledger outage"); };

        assertEquals(0, poller(failure).pollOnce(10));

        assertAll(
                () -> assertEquals(0, count("bot.bot_events", "event_type = 'INITIAL_CAPITAL_POSTED'")),
                () -> assertEquals(0, count("trading.ledger_transactions", "true")),
                () -> assertEquals(0, count("operations.outbox_messages", "event_type = 'ROOM_EVALUATION_ACCOUNT_OPENED'")),
                () -> assertEquals(1, count("operations.outbox_consumer_receipts", "status = 'RETRYABLE_FAILURE'")));
    }

    @Test
    void expiredLeaseCannotBeStolenWhileTheEffectTransactionOwnsTheReceiptFence() throws Exception {
        seedRequest(message(1), "room-open:" + PARTICIPATION, "100000.00000000");
        MutableClock time = new MutableClock(NOW);
        CountDownLatch effectEntered = new CountDownLatch(1);
        CountDownLatch releaseEffect = new CountDownLatch(1);
        BotEventStore delegate = new PostgresBotEventStore(jdbc, transactions);
        BotEventStore blocking = new BotEventStore() {
            @Override public BotEvent appendOrLoad(BotEventAppend append) {
                effectEntered.countDown();
                try {
                    if (!releaseEffect.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return delegate.appendOrLoad(append);
            }
            @Override public Optional<BotEvent> find(UUID botId, UUID eventId) {
                return delegate.find(botId, eventId);
            }
        };
        var first = poller(blocking, new PostgresLedgerStore(jdbc, transactions), time, "worker-1", Duration.ofSeconds(1));
        var second = poller(delegate, new PostgresLedgerStore(jdbc, transactions), time, "worker-2", Duration.ofSeconds(1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> first.pollOnce(1));
            assertEquals(true, effectEntered.await(5, TimeUnit.SECONDS));
            time.set(NOW.plusSeconds(2));
            var secondResult = executor.submit(() -> second.pollOnce(1));
            try {
                secondResult.get(300, TimeUnit.MILLISECONDS);
                throw new AssertionError("second worker entered an effect while the receipt fence was held");
            } catch (TimeoutException expected) {
                // The receipt row lock fences the second worker until the first effect commits.
            }
            releaseEffect.countDown();
            assertEquals(1, firstResult.get(5, TimeUnit.SECONDS));
            assertEquals(0, secondResult.get(5, TimeUnit.SECONDS));
        }
        assertAll(
                () -> assertEquals(1, count("bot.bot_events", "event_type = 'INITIAL_CAPITAL_POSTED'")),
                () -> assertEquals(1, count("trading.ledger_transactions", "true")),
                () -> assertEquals(1, count("operations.outbox_messages", "event_type = 'ROOM_EVALUATION_ACCOUNT_OPENED'")));
    }

    private RoomEvaluationAccountOpenPoller poller(LedgerStore ledger) {
        return poller(new PostgresBotEventStore(jdbc, transactions), ledger,
                Clock.fixed(NOW, ZoneOffset.UTC), "root-181-test", Duration.ofSeconds(30));
    }

    private RoomEvaluationAccountOpenPoller poller(
            BotEventStore events, LedgerStore ledger, Clock clock, String worker, Duration lease) {
        return new RoomEvaluationAccountOpenPoller(
                jdbc, events, ledger, new ObjectMapper(), clock, worker, lease,
                Duration.ofSeconds(30), 3, transactions);
    }

    private void seedRequest(UUID messageId, String producerKey, String initialCash) {
        String payload = """
                {"commandId":"%s","messageId":"%s","producerIdempotencyKey":"%s",
                 "roomId":"%s","participationId":"%s","botId":"%s","evaluationSegmentId":"%s",
                 "initialCash":"%s","currency":"USD","feePolicyVersionId":"%s",
                 "buyingPowerPolicyVersionId":"%s","effectiveAt":"%s"}
                """.formatted(COMMAND, messageId, producerKey, ROOM, PARTICIPATION, BOT, SEGMENT,
                initialCash, FEE_POLICY, BUYING_POWER_POLICY, NOW);
        jdbc.sql("""
                        insert into operations.outbox_messages (
                            id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                            event_schema_version, payload_document, idempotency_key,
                            producer_idempotency_key, created_at)
                        values (:id, 'room-performance', :participation, 1, :eventType,
                            :schemaVersion, cast(:payload as jsonb), :rowKey, :producerKey, :at)
                        """)
                .param("id", messageId).param("participation", PARTICIPATION)
                .param("eventType", RoomEvaluationAccountOpenPoller.REQUEST_TYPE)
                .param("schemaVersion", RoomEvaluationAccountOpenPoller.REQUEST_SCHEMA)
                .param("payload", payload).param("rowKey", "request-row:" + messageId)
                .param("producerKey", producerKey).param("at", NOW.atOffset(ZoneOffset.UTC)).update();
    }

    private int count(String table, String predicate) {
        return jdbc.sql("select count(*) from " + table + " where " + predicate).query(Integer.class).single();
    }

    private static UUID message(int sequence) {
        return UUID.nameUUIDFromBytes(("room-account-request:" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
