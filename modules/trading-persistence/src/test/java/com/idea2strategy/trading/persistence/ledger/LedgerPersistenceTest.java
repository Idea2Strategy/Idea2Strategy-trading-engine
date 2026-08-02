package com.idea2strategy.trading.persistence.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.ledger.LedgerCommandConflictException;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.containers.PostgreSQLContainer;

class LedgerPersistenceTest {
    private static final UUID FIXTURE_SOURCE =
            UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final Instant FIXTURE_TIME = Instant.parse("2026-07-31T14:30:00.200Z");
    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTransactionManager transactions;
    private static JdbcClient jdbc;
    private static PostgresLedgerStore store;
    private static JooqLedgerQuery query;

    @BeforeAll
    static void setup() {
        String externalUrl = System.getenv("TEST_POSTGRES_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        } else {
            dataSource = new DriverManagerDataSource(externalUrl,
                    System.getenv("TEST_POSTGRES_USER"), System.getenv("TEST_POSTGRES_PASSWORD"));
        }
        transactions = new JdbcTransactionManager(dataSource);
        jdbc = JdbcClient.create(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        store = newStore();
        query = new JooqLedgerQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @AfterAll
    static void shutdown() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.sql("truncate table trading.official_ledger_transaction cascade").update();
    }

    @Test
    void postsComFFixtureMeaningOnceAndReconstructsAfterRestart() {
        LedgerTransaction fixture = LedgerTransaction.standard(FIXTURE_SOURCE, FIXTURE_TIME, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.12"))));
        UUID commandId = UUID.randomUUID();
        var command = new PostLedgerTransactionCommand(commandId, fixture);

        assertEquals(fixture, store.append(command));
        assertEquals(fixture, newStore().append(command));
        assertEquals(fixture, new JooqLedgerQuery(DSL.using(dataSource, SQLDialect.POSTGRES))
                .findBySourceEventId(FIXTURE_SOURCE).orElseThrow());
        assertEquals(2, jdbc.sql("select count(*) from trading.official_ledger_entry")
                .query(Integer.class).single());
    }

    @Test
    void preservesExactDecimalsAndRebuildsAccountTotalsFromEntries() {
        store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), LedgerTransaction.standard(
                UUID.randomUUID(), FIXTURE_TIME, List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("0.000000000000000001")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("0.000000000000000001"))))));

        var totals = query.accountTotals();
        assertEquals(2, totals.size());
        assertTrue(totals.stream().allMatch(total ->
                total.debits().add(total.credits()).compareTo(new BigDecimal("0.000000000000000001")) == 0));
    }

    @Test
    void keepsOriginalReversalAndCorrectionAsImmutableLineage() {
        LedgerTransaction original = fixturePosting();
        store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), original));
        LedgerTransaction reversal = LedgerTransaction.reversal(UUID.randomUUID(), FIXTURE_TIME.plusSeconds(1), original);
        LedgerTransaction correction = LedgerTransaction.correction(UUID.randomUUID(), FIXTURE_TIME.plusSeconds(2),
                original.transactionId(), List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("1.25")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("1.25"))));

        store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), reversal));
        store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), correction));

        assertEquals(original, query.findByTransactionId(original.transactionId()).orElseThrow());
        assertEquals(original.transactionId(), query.findByTransactionId(reversal.transactionId())
                .orElseThrow().reversesTransactionId());
        assertEquals(original.transactionId(), query.findByTransactionId(correction.transactionId())
                .orElseThrow().correctsTransactionId());
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                update trading.official_ledger_entry set amount=1 where entry_id=:id
                """).param("id", original.entries().get(0).entryId()).update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                delete from trading.official_ledger_transaction where transaction_id=:id
                """).param("id", original.transactionId()).update());
    }

    @Test
    void rejectsCommandReuseAndDifferentMeaningForOneSourceEvent() {
        UUID commandId = UUID.randomUUID();
        LedgerTransaction original = fixturePosting();
        store.append(new PostLedgerTransactionCommand(commandId, original));
        LedgerTransaction another = LedgerTransaction.standard(UUID.randomUUID(), FIXTURE_TIME.plusSeconds(1), List.of(
                LedgerEntryDraft.debit("FEE_EXPENSE", "USD", BigDecimal.ONE),
                LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));
        assertThrows(LedgerCommandConflictException.class,
                () -> store.append(new PostLedgerTransactionCommand(commandId, another)));

        LedgerTransaction sourceConflict = LedgerTransaction.standard(original.sourceEventId(),
                FIXTURE_TIME.plusSeconds(5), List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("99")),
                        LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("99"))));
        assertThrows(LedgerCommandConflictException.class,
                () -> store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), sourceConflict)));
    }

    @Test
    void rejectsLineageThatReferencesAnUnknownTransaction() {
        LedgerTransaction correction = LedgerTransaction.correction(UUID.randomUUID(), FIXTURE_TIME,
                UUID.randomUUID(), List.of(
                        LedgerEntryDraft.debit("REALIZED_PNL", "USD", BigDecimal.ONE),
                        LedgerEntryDraft.credit("CASH", "USD", BigDecimal.ONE)));
        assertThrows(LedgerCommandConflictException.class,
                () -> store.append(new PostLedgerTransactionCommand(UUID.randomUUID(), correction)));
    }

    @Test
    void databaseConstraintRejectsAnUnbalancedDirectPostingAtCommit() {
        UUID transactionId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        assertThrows(TransactionSystemException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into trading.official_ledger_transaction
                        (transaction_id, source_event_id, posted_at, posting_kind)
                    values (:id, :source, :postedAt, 'STANDARD')
                    """).param("id", transactionId).param("source", sourceEventId)
                    .param("postedAt", FIXTURE_TIME.atOffset(java.time.ZoneOffset.UTC)).update();
            jdbc.sql("""
                    insert into trading.official_ledger_entry
                        (entry_id, transaction_id, entry_sequence, account_code, direction,
                         currency, amount, source_event_id)
                    values (:entryId, :transactionId, 1, 'SECURITY', 'DEBIT', 'USD', 10, :source)
                    """).param("entryId", UUID.randomUUID()).param("transactionId", transactionId)
                    .param("source", sourceEventId).update();
        }));
        assertEquals(0, jdbc.sql("select count(*) from trading.official_ledger_transaction")
                .query(Integer.class).single());
    }

    private static LedgerTransaction fixturePosting() {
        return LedgerTransaction.standard(FIXTURE_SOURCE, FIXTURE_TIME, List.of(
                LedgerEntryDraft.debit("SECURITY", "USD", new BigDecimal("210.12")),
                LedgerEntryDraft.credit("CASH", "USD", new BigDecimal("210.12"))));
    }

    private static PostgresLedgerStore newStore() {
        return new PostgresLedgerStore(JdbcClient.create(dataSource), transactions);
    }
}
