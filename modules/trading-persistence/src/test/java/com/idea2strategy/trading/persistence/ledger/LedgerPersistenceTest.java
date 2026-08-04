package com.idea2strategy.trading.persistence.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.ledger.LedgerCommandConflictException;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the double entry write path against the real canonical schema.
 *
 * <p>Before this, postings landed in the private {@code trading.official_ledger_*} tables, which
 * nothing else in the platform reads. The canonical columns are asserted directly rather than only
 * round tripping the aggregate, because landing in the right shape is the whole point.
 */
@Testcontainers(disabledWithoutDocker = true)
class LedgerPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("b3000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT = UUID.fromString("b3000000-0000-4000-8000-000000000002");
    private static final UUID PARTITION = UUID.fromString("b4000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT_EVENT = UUID.fromString("e4000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-08-02T09:00:00.200Z");

    /** Every posting needs a real official event to name, so a pool of them is seeded once. */
    private static final List<UUID> EVENTS = List.of(
            UUID.fromString("e3000000-0000-4000-8000-000000000001"),
            UUID.fromString("e3000000-0000-4000-8000-000000000002"),
            UUID.fromString("e3000000-0000-4000-8000-000000000003"),
            UUID.fromString("e3000000-0000-4000-8000-000000000004"),
            UUID.fromString("e3000000-0000-4000-8000-000000000005"),
            UUID.fromString("e3000000-0000-4000-8000-000000000006"),
            UUID.fromString("e3000000-0000-4000-8000-000000000007"),
            UUID.fromString("e3000000-0000-4000-8000-000000000008"));

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactionManager;
    private static PostgresLedgerStore store;
    private static JooqLedgerQuery query;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactionManager = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqLedgerQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        // Bots, partitions and official events belong to other services. They are seeded with
        // referential triggers off exactly as the canonical contract fixtures do, on one connection
        // because session_replication_role is session state. The ledger writes under test then run
        // with the triggers back on, so their own foreign keys are genuinely checked.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch(bot(BOT, "Ledger bot"));
            statement.addBatch(bot(OTHER_BOT, "Another ledger bot"));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
            for (int index = 0; index < EVENTS.size(); index++) {
                statement.addBatch(botEvent(EVENTS.get(index), BOT, index + 1L));
            }
            statement.addBatch(botEvent(OTHER_BOT_EVENT, OTHER_BOT, 1L));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    private static String bot(UUID id, String name) {
        return """
                insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                    lifecycle_changed_at, created_at, execution_eligible_from)
                values ('%s', 'a3000000-0000-4000-8000-000000000001', 'BASIC', '%s', 'RUNNING',
                    '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00')
                """.formatted(id, name);
    }

    /** {@code bot.bot_events.correlation_id} is NOT NULL, so the seed has to supply one. */
    private static String botEvent(UUID id, UUID botId, long sequence) {
        return """
                insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                    event_schema_version, correlation_id, idempotency_key, occurred_at,
                    received_at, summary_document)
                values ('%s', '%s', %d, 'LEDGER_TRANSACTION_POSTED', 'v1', gen_random_uuid(),
                    'seed-ledger-%d', '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00', '{}')
                """.formatted(id, botId, sequence, sequence);
    }

    /**
     * The three ledger tables are cleared in one transaction. This repository's own balance
     * contribution refuses, at commit, a transaction left without balanced entries, so removing the
     * headers and the entries separately would trip the very trigger these tests rely on.
     */
    @BeforeEach
    void clearLedger() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.addBatch("delete from trading.ledger_entries");
            statement.addBatch("delete from trading.ledger_transactions");
            statement.addBatch("delete from trading.ledger_accounts");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void aPostingLandsInTheCanonicalShape() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("210.12"));

        assertEquals(posting, store.append(PostLedgerTransactionCommand.botWide(BOT, posting)));

        LedgerTransactionPersistenceView stored = query.findTransaction(posting.transactionId()).orElseThrow();
        var debit = stored.entries().get(0);
        var credit = stored.entries().get(1);
        assertAll(
                () -> assertEquals(BOT, stored.botId()),
                () -> assertNull(stored.partitionId()),
                () -> assertEquals(EVENTS.get(0), stored.botEventId()),
                () -> assertEquals("LEDGER_POSTING", stored.transactionType()),
                () -> assertEquals("BOT_EVENT:" + EVENTS.get(0), stored.transactionKey()),
                () -> assertEquals("BOT_EVENT", stored.sourceType()),
                () -> assertEquals(EVENTS.get(0), stored.sourceId()),
                () -> assertEquals("USD", stored.currencyCode()),
                () -> assertNull(stored.reversalOfTransactionId()),
                () -> assertEquals(AT, stored.occurredAt()),
                () -> assertEquals("LEDGER_TRANSACTION_POSTED", stored.descriptionCode()),
                () -> assertEquals(2, stored.entries().size()),
                () -> assertEquals(1, debit.entrySequence()),
                () -> assertEquals("DEBIT", debit.direction()),
                () -> assertEquals("BOT:SECURITY:USD", debit.accountKey()),
                () -> assertEquals("SECURITY", debit.accountType()),
                () -> assertEquals("USD", debit.accountCurrencyCode()),
                () -> assertNull(debit.quantity()),
                () -> assertNull(debit.orderComponentId()),
                () -> assertEquals(0, new BigDecimal("210.12").compareTo(debit.amount())),
                () -> assertTrue(debit.entryHash().matches("[0-9a-f]{64}")),
                () -> assertEquals(2, credit.entrySequence()),
                () -> assertEquals("CREDIT", credit.direction()),
                () -> assertEquals("BOT:CASH:USD", credit.accountKey()),
                () -> assertNotEquals(debit.entryHash(), credit.entryHash()),
                () -> assertEquals(posting, store.load(BOT, posting.transactionId()).orElseThrow()));
    }

    /** The posting is findable by the event that caused it, which is the canonical unique handle. */
    @Test
    void thePostingIsReachableFromTheOfficialEventThatCausedIt() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("12.5"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, posting));

        assertEquals(posting.transactionId(),
                query.findByBotEventId(EVENTS.get(0)).orElseThrow().transactionId());
    }

    @Test
    void redeliveringIdenticalWorkReturnsTheStoredPostingInsteadOfASecond() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("210.12"));
        var command = PostLedgerTransactionCommand.botWide(BOT, posting);

        assertEquals(posting, store.append(command));
        assertEquals(posting, newStore().append(command));

        assertEquals(1, query.countTransactions());
        assertEquals(2, query.countEntries());
        assertEquals(2, query.countAccounts());
    }

    @Test
    void concurrentRedeliveryOfOnePostingStillProducesExactlyOneTransaction() throws Exception {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("77.25"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<LedgerTransaction> append = () -> {
            ready.countDown();
            start.await();
            return newStore().append(PostLedgerTransactionCommand.botWide(BOT, posting));
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(append);
            var second = executor.submit(append);
            assertTrue(ready.await(10, SECONDS));
            start.countDown();

            assertEquals(posting, first.get(30, SECONDS));
            assertEquals(posting, second.get(30, SECONDS));
        }
        assertEquals(1, query.countTransactions());
        assertEquals(2, query.countEntries());
    }

    /**
     * One official event, two different postings. The canonical unique on {@code bot_event_id} is
     * what stops the second, and the derived transaction id is what makes it recognisable as
     * different work rather than a redelivery.
     */
    @Test
    void aBotEventAlreadyRecordingDifferentWorkIsAConflictInsteadOfAnOverwrite() {
        LedgerTransaction original = standard(EVENTS.get(0), new BigDecimal("210.12"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, original));

        LedgerTransaction divergent = standard(EVENTS.get(0), new BigDecimal("99"));
        assertNotEquals(original.transactionId(), divergent.transactionId());

        assertThrows(LedgerCommandConflictException.class,
                () -> newStore().append(PostLedgerTransactionCommand.botWide(BOT, divergent)));
        assertEquals(1, query.countTransactions());
        assertEquals(0, new BigDecimal("210.12").compareTo(
                query.findTransaction(original.transactionId()).orElseThrow().entries().get(0).amount()));
    }

    /**
     * A posting cannot claim an event of another bot. The canonical foreign key is composite over
     * {@code (bot_id, bot_event_id)}, so the cause has to belong to the bot booking the posting.
     */
    @Test
    void aPostingMustNameAnOfficialEventOfItsOwnBot() {
        LedgerTransaction posting = standard(OTHER_BOT_EVENT, new BigDecimal("1"));

        assertThrows(DataAccessException.class,
                () -> store.append(PostLedgerTransactionCommand.botWide(BOT, posting)));
        assertEquals(0, query.countTransactions());
    }

    /**
     * Partition scope is not decoration. {@code ledger_entries} reaches its transaction and its
     * account through foreign keys composite over {@code partition_id}, and PostgreSQL treats a
     * composite foreign key containing a NULL as satisfied, so a bot wide posting is the one shape
     * where neither key checks anything.
     */
    @Test
    void aPartitionedPostingScopesEveryCanonicalRowAndIsHeldTogetherByTheCompositeKey() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("42"));
        store.append(new PostLedgerTransactionCommand(BOT, PARTITION, posting));

        LedgerTransactionPersistenceView stored = query.findTransaction(posting.transactionId()).orElseThrow();
        assertAll(
                () -> assertEquals(PARTITION, stored.partitionId()),
                () -> assertTrue(stored.entries().stream().allMatch(row -> PARTITION.equals(row.partitionId()))),
                () -> assertTrue(stored.entries().stream()
                        .allMatch(row -> PARTITION.equals(row.accountPartitionId()))),
                () -> assertEquals("PARTITION:" + PARTITION + ":SECURITY:USD",
                        stored.entries().get(0).accountKey()));

        assertThrows(DataAccessException.class, () -> jdbc
                .sql("delete from trading.ledger_transactions where id = :id")
                .param("id", posting.transactionId())
                .update());
        assertEquals(1, query.countTransactions());
    }

    /** Root #181's direct transaction FK closes the nullable composite-FK gap for bot-wide cash. */
    @Test
    void aBotWideHeaderCannotBeDeletedWhileItsEntriesRemain() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("42"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, posting));

        assertThrows(DataAccessException.class, () -> jdbc
                .sql("delete from trading.ledger_transactions where id = :id")
                .param("id", posting.transactionId())
                .update());

        assertEquals(1, query.countTransactions());
        assertEquals(2, query.countEntries());
    }

    /**
     * The canonical header has one lineage column and no correction column, so a reversal and a
     * correction both name their target through {@code reversal_of_transaction_id} and
     * {@code transaction_type} is what tells the two apart. Both round trip.
     */
    @Test
    void reversalAndCorrectionShareTheOneCanonicalLineageColumn() {
        LedgerTransaction original = standard(EVENTS.get(0), new BigDecimal("210.12"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, original));
        LedgerTransaction reversal = LedgerTransaction.reversal(EVENTS.get(1), AT.plusSeconds(1), original);
        LedgerTransaction correction = LedgerTransaction.correction(EVENTS.get(2), AT.plusSeconds(2),
                original.transactionId(), List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("1.25")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("1.25"))));

        store.append(PostLedgerTransactionCommand.botWide(BOT, reversal));
        store.append(PostLedgerTransactionCommand.botWide(BOT, correction));

        var storedReversal = query.findTransaction(reversal.transactionId()).orElseThrow();
        var storedCorrection = query.findTransaction(correction.transactionId()).orElseThrow();
        assertAll(
                () -> assertEquals("LEDGER_REVERSAL", storedReversal.transactionType()),
                () -> assertEquals("LEDGER_TRANSACTION_REVERSED", storedReversal.descriptionCode()),
                () -> assertEquals(original.transactionId(), storedReversal.reversalOfTransactionId()),
                () -> assertEquals("LEDGER_CORRECTION", storedCorrection.transactionType()),
                () -> assertEquals("LEDGER_TRANSACTION_CORRECTED", storedCorrection.descriptionCode()),
                () -> assertEquals(original.transactionId(), storedCorrection.reversalOfTransactionId()),
                () -> assertEquals(reversal, store.load(BOT, reversal.transactionId()).orElseThrow()),
                () -> assertEquals(correction, store.load(BOT, correction.transactionId()).orElseThrow()),
                () -> assertEquals(original, store.load(BOT, original.transactionId()).orElseThrow()));
    }

    /**
     * The private schema had a partial unique index for this. The canonical model has none, so the
     * store reads instead, and the guard is honest about being a read: it stops the ordinary double
     * reversal, not two racing ones.
     */
    @Test
    void refusesASecondReversalOfTheSameTransaction() {
        LedgerTransaction original = standard(EVENTS.get(0), new BigDecimal("210.12"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, original));
        store.append(PostLedgerTransactionCommand.botWide(
                BOT, LedgerTransaction.reversal(EVENTS.get(1), AT.plusSeconds(1), original)));

        LedgerTransaction again = LedgerTransaction.reversal(EVENTS.get(2), AT.plusSeconds(2), original);
        assertThrows(LedgerCommandConflictException.class,
                () -> newStore().append(PostLedgerTransactionCommand.botWide(BOT, again)));
        assertEquals(2, query.countTransactions());
    }

    @Test
    void refusesLineageThatReferencesAnUnknownTransaction() {
        LedgerTransaction correction = LedgerTransaction.correction(EVENTS.get(0), AT, UUID.randomUUID(), List.of(
                LedgerEntryDraft.debit("REALIZED_PNL", "USD", BigDecimal.ONE),
                LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));

        assertThrows(LedgerCommandConflictException.class,
                () -> store.append(PostLedgerTransactionCommand.botWide(BOT, correction)));
        assertEquals(0, query.countTransactions());
    }

    /**
     * The deferred balance trigger this repository contributed is what makes a header without its
     * entries impossible, so a one sided posting written straight to the tables cannot commit.
     */
    @Test
    void theDeferredBalanceTriggerRefusesAOneSidedPostingAtCommit() {
        UUID transactionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        assertThrows(TransactionSystemException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.sql("""
                            insert into trading.ledger_accounts (id, bot_id, account_key, account_type,
                                currency_code, created_at)
                            values (:id, :botId, 'BOT:SECURITY:USD', 'SECURITY', 'USD', :createdAt)
                            """)
                            .param("id", accountId)
                            .param("botId", BOT)
                            .param("createdAt", AT.atOffset(ZoneOffset.UTC))
                            .update();
                    jdbc.sql("""
                            insert into trading.ledger_transactions (id, bot_id, bot_event_id,
                                transaction_type, transaction_key, source_type, source_id,
                                currency_code, occurred_at, description_code)
                            values (:id, :botId, :eventId, 'LEDGER_POSTING', 'BOT_EVENT:probe',
                                'BOT_EVENT', :eventId, 'USD', :occurredAt, 'LEDGER_TRANSACTION_POSTED')
                            """)
                            .param("id", transactionId)
                            .param("botId", BOT)
                            .param("eventId", EVENTS.get(0))
                            .param("occurredAt", AT.atOffset(ZoneOffset.UTC))
                            .update();
                    jdbc.sql("""
                            insert into trading.ledger_entries (id, bot_id, transaction_id,
                                ledger_account_id, entry_sequence, direction, amount, entry_hash)
                            values (gen_random_uuid(), :botId, :transactionId, :accountId, 1,
                                'DEBIT', 10.00000000, :hash)
                            """)
                            .param("botId", BOT)
                            .param("transactionId", transactionId)
                            .param("accountId", accountId)
                            .param("hash", "d".repeat(64))
                            .update();
                }));

        assertEquals(0, query.countTransactions());
        assertEquals(0, query.countEntries());
    }

    /** Nothing survives a write that failed halfway, so a retry sees an empty ledger, not a header. */
    @Test
    void aFailedWriteLeavesNoPartialPosting() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("210.12"));
        TransactionTemplate ddlTransaction = new TransactionTemplate(transactionManager);

        assertThrows(DataAccessException.class, () -> ddlTransaction.executeWithoutResult(status -> {
            jdbc.sql("alter table trading.ledger_entries rename to ledger_entries_unavailable").update();
            store.append(PostLedgerTransactionCommand.botWide(BOT, posting));
        }));

        assertEquals(0, query.countTransactions());
        assertEquals(0, query.countEntries());
    }

    /**
     * An account is a fact, not an allocation: the second posting that books to CASH reuses the row
     * the first one opened rather than creating a second CASH.
     */
    @Test
    void accountsAreOpenedOnceAndTotalsRebuildFromTheEntriesAlone() {
        store.append(PostLedgerTransactionCommand.botWide(BOT, standard(EVENTS.get(0), new BigDecimal("10"))));
        store.append(PostLedgerTransactionCommand.botWide(BOT, LedgerTransaction.standard(
                EVENTS.get(1), AT.plusSeconds(1), List.of(
                        LedgerEntryDraft.debit("FEE_EXPENSE", "USD", new BigDecimal("2.50")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("2.50"))))));

        assertEquals(3, query.countAccounts());
        List<JooqLedgerQuery.AccountTotals> totals = query.accountTotals();
        assertAll(
                () -> assertEquals(
                        List.of("BOT:CASH:USD", "BOT:FEE_EXPENSE:USD", "BOT:SECURITY:USD"),
                        totals.stream().map(JooqLedgerQuery.AccountTotals::accountKey).toList()),
                () -> assertEquals(0, new BigDecimal("12.50").compareTo(totals.get(0).credits())),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(totals.get(0).debits())),
                () -> assertEquals(0, new BigDecimal("2.50").compareTo(totals.get(1).debits())),
                () -> assertEquals(0, new BigDecimal("10").compareTo(totals.get(2).debits())));
    }

    /**
     * The aggregate is compared by value to recognise a redelivery and PostgreSQL always returns
     * {@code numeric(24,8)} at scale 8, so the finest amount the ledger can hold has to survive the
     * round trip unchanged.
     */
    @Test
    void theFinestCanonicalAmountRoundTripsUnchanged() {
        LedgerTransaction posting = standard(EVENTS.get(0), new BigDecimal("0.00000001"));
        store.append(PostLedgerTransactionCommand.botWide(BOT, posting));

        assertEquals(posting, newStore().append(PostLedgerTransactionCommand.botWide(BOT, posting)));
        assertEquals(0, new BigDecimal("0.00000001").compareTo(
                query.findTransaction(posting.transactionId()).orElseThrow().entries().get(0).amount()));
    }

    private static LedgerTransaction standard(UUID sourceEventId, BigDecimal amount) {
        return LedgerTransaction.standard(sourceEventId, AT, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", amount),
                LedgerEntryDraft.credit("CASH", "USD", amount)));
    }

    private static PostgresLedgerStore newStore() {
        return new PostgresLedgerStore(JdbcClient.create(dataSource), transactionManager);
    }
}
