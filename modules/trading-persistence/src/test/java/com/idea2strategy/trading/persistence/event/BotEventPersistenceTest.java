package com.idea2strategy.trading.persistence.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.event.BotEventConflictException;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the bot event append against the real canonical schema.
 *
 * <p>This is the cause every canonical trading write needs: thirteen canonical tables carry a NOT
 * NULL foreign key into {@code bot.bot_events}, so nothing else in the write path can be migrated
 * until an append exists that respects both canonical uniqueness rules.
 */
@Testcontainers(disabledWithoutDocker = true)
class BotEventPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT = UUID.fromString("b2000000-0000-4000-8000-000000000002");
    private static final UUID CORRELATION = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-08-02T09:00:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresBotEventStore store;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        store = new PostgresBotEventStore(jdbc, new JdbcTransactionManager(dataSource));

        // The owning account belongs to another service, so it is bypassed exactly as the canonical
        // contract fixtures do. This must run on one connection because session_replication_role is
        // session state and the data source hands out a new connection per statement.
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            for (UUID bot : List.of(BOT, OTHER_BOT)) {
                statement.addBatch("""
                        insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                            lifecycle_changed_at, created_at, execution_eligible_from)
                        values ('%s', 'a2000000-0000-4000-8000-000000000001', 'BASIC',
                            'Bot event bot', 'RUNNING', '2026-08-02T09:00:00+00',
                            '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00')
                        """.formatted(bot));
            }
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    private static java.time.OffsetDateTime offset() {
        return AT.atOffset(java.time.ZoneOffset.UTC);
    }

    /**
     * Ledger rows are removed in one transaction with their entries, because this repository's own
     * balance contribution rejects a transaction that is left without balanced entries at commit.
     */
    @BeforeEach
    void clearEvents() throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.addBatch("delete from trading.ledger_entries");
            statement.addBatch("delete from trading.ledger_transactions");
            statement.addBatch("delete from trading.ledger_accounts");
            statement.addBatch("delete from bot.bot_events");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void anAppendedEventIsReadableAndCarriesTheServiceSchemaVersion() {
        BotEvent stored = store.appendOrLoad(append(BotEventType.ORDER_ACCEPTED, "order-1"));

        assertEquals(BOT, stored.botId());
        assertEquals(1L, stored.eventSequence());
        assertEquals(BotEventType.ORDER_ACCEPTED, stored.type());
        assertEquals("ORDER_ACCEPTED:order-1", stored.idempotencyKey());
        assertEquals(AT, stored.occurredAt());
        assertEquals(stored, store.find(BOT, stored.eventId()).orElseThrow());
        assertEquals(
                BotEventAppend.EVENT_SCHEMA_VERSION,
                jdbc.sql("select event_schema_version from bot.bot_events where id = ?")
                        .param(stored.eventId()).query(String.class).single());
    }

    @Test
    void redeliveringIdenticalWorkReturnsTheSameRowInsteadOfASecond() {
        BotEvent first = store.appendOrLoad(append(BotEventType.ORDER_FILLED, "fill-1"));
        BotEvent again = store.appendOrLoad(append(BotEventType.ORDER_FILLED, "fill-1"));

        assertEquals(first, again);
        assertEquals(1, count());
    }

    @Test
    void aKeyAlreadyRecordingDifferentWorkFailsInsteadOfOverwriting() {
        store.appendOrLoad(append(BotEventType.ORDER_FILLED, "fill-2"));

        BotEventAppend divergent = new BotEventAppend(
                BOT, BotEventType.ORDER_FILLED,
                BotEventAppend.idempotencyKey(BotEventType.ORDER_FILLED, "fill-2"),
                CORRELATION, null, AT.plusSeconds(1), AT.plusSeconds(1), "{}");

        BotEventConflictException failure =
                assertThrows(BotEventConflictException.class, () -> store.appendOrLoad(divergent));
        assertTrue(failure.getMessage().contains("already records different work"));
        assertEquals(1, count());
    }

    @Test
    void theSameKeyOnAnotherBotIsDifferentWorkAndIsAccepted() {
        BotEvent mine = store.appendOrLoad(append(BotEventType.ORDER_ACCEPTED, "order-9"));
        BotEvent theirs = store.appendOrLoad(new BotEventAppend(
                OTHER_BOT, BotEventType.ORDER_ACCEPTED,
                BotEventAppend.idempotencyKey(BotEventType.ORDER_ACCEPTED, "order-9"),
                CORRELATION, null, AT, AT, "{}"));

        assertNotEquals(mine.eventId(), theirs.eventId());
        assertEquals(1L, theirs.eventSequence(), "each bot has its own sequence");
        assertEquals(2, count());
    }

    @Test
    void everySequenceWithinOneBotIsDistinctUnderConcurrentAppends() throws Exception {
        int appends = 24;
        List<Callable<BotEvent>> work = new ArrayList<>();
        for (int index = 0; index < appends; index++) {
            String subject = "concurrent-" + index;
            work.add(() -> store.appendOrLoad(append(BotEventType.LEDGER_TRANSACTION_POSTED, subject)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<BotEvent> results = new ArrayList<>();
        try {
            for (Future<BotEvent> future : pool.invokeAll(work)) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        Set<Long> sequences = new HashSet<>();
        results.forEach(event -> sequences.add(event.eventSequence()));
        assertEquals(appends, results.size());
        assertEquals(appends, sequences.size(), "concurrent appends reused a sequence");
        assertEquals(appends, count());
    }

    @Test
    void concurrentRedeliveryOfOneKeyStillProducesExactlyOneRow() throws Exception {
        List<Callable<BotEvent>> work = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            work.add(() -> store.appendOrLoad(append(BotEventType.ORDER_FILLED, "duplicate")));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        Set<UUID> ids = new HashSet<>();
        try {
            for (Future<BotEvent> future : pool.invokeAll(work)) {
                ids.add(future.get(60, TimeUnit.SECONDS).eventId());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ids.size(), "redelivery produced more than one event");
        assertEquals(1, count());
    }

    @Test
    void aCausationChainIsPreserved() {
        BotEvent trigger = store.appendOrLoad(append(BotEventType.ORDER_ACCEPTED, "order-3"));
        BotEvent caused = store.appendOrLoad(
                append(BotEventType.ORDER_FILLED, "fill-3").causedBy(trigger.eventId()));

        assertEquals(trigger.eventId(), caused.causationEventId());
        assertTrue(caused.eventSequence() > trigger.eventSequence());
    }

    @Test
    void aRoutedTriggerPrefixIsRefusedSoThisServiceCannotCollideWithTheRouter() {
        for (String reserved : List.of("PRICE:AAPL:2026-08-02T09:00:00Z", "SCHEDULE:ONE_MINUTE:1")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new BotEventAppend(BOT, BotEventType.ORDER_ACCEPTED, reserved,
                            CORRELATION, null, AT, AT, "{}"));
            assertTrue(failure.getMessage().contains("routed trigger prefix"));
        }
    }

    @Test
    void anEventCanCauseACanonicalTradingWrite() throws Exception {
        BotEvent event = store.appendOrLoad(append(BotEventType.LEDGER_TRANSACTION_POSTED, "txn-1"));

        // The point of this aggregate: a canonical trading row can now name a real cause. The
        // posting is written whole, because this repository's own ledger balance contribution
        // rejects a transaction without balanced entries at commit.
        UUID transactionId = UUID.randomUUID();
        UUID cashAccount = UUID.randomUUID();
        UUID positionAccount = UUID.randomUUID();
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.addBatch(ledgerAccount(cashAccount, "BOT2:CASH:USD", "CASH"));
            statement.addBatch(ledgerAccount(positionAccount, "BOT2:CAPITAL:USD", "CONTRIBUTED_CAPITAL"));
            statement.addBatch("""
                    insert into trading.ledger_transactions (
                        id, bot_id, bot_event_id, transaction_type, transaction_key, source_type,
                        source_id, currency_code, occurred_at, description_code)
                    values ('%s', '%s', '%s', 'INITIAL_CAPITAL', 'BOT2:CAPITAL:1',
                        'INITIAL_CAPITAL', gen_random_uuid(), 'USD', '2026-08-02T09:00:00+00',
                        'CAPITAL_DEPOSITED')
                    """.formatted(transactionId, BOT, event.eventId()));
            statement.addBatch(ledgerEntry(transactionId, cashAccount, 1, "DEBIT"));
            statement.addBatch(ledgerEntry(transactionId, positionAccount, 2, "CREDIT"));
            statement.executeBatch();
            connection.commit();
        }

        assertEquals(
                1,
                jdbc.sql("select count(*) from trading.ledger_transactions where bot_event_id = ?")
                        .param(event.eventId()).query(Integer.class).single());
    }

    private static String ledgerAccount(UUID id, String key, String type) {
        return """
                insert into trading.ledger_accounts (id, bot_id, account_key, account_type,
                    currency_code, created_at)
                values ('%s', '%s', '%s', '%s', 'USD', '2026-08-02T09:00:00+00')
                """.formatted(id, BOT, key, type);
    }

    private static String ledgerEntry(UUID transactionId, UUID accountId, int sequence, String direction) {
        return """
                insert into trading.ledger_entries (id, bot_id, transaction_id, ledger_account_id,
                    entry_sequence, direction, amount, entry_hash)
                values (gen_random_uuid(), '%s', '%s', '%s', %d, '%s', 100.00000000, '%s')
                """.formatted(BOT, transactionId, accountId, sequence, direction, "d".repeat(64));
    }

    private static BotEventAppend append(BotEventType type, String subject) {
        return BotEventAppend.of(BOT, type, subject, CORRELATION, AT, "{\"probe\":true}");
    }

    private static int count() {
        return jdbc.sql("select count(*) from bot.bot_events").query(Integer.class).single();
    }
}
