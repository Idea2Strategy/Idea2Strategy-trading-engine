package com.idea2strategy.trading.persistence.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the two canonical invariants this repository contributes, against the real canonical
 * schema rather than the private compatibility schema.
 *
 * <p>Both invariants are already written into the approved canonical model but were not enforced by
 * anything the shipped bundle creates:
 *
 * <ul>
 *   <li>{@code trading.ledger_entries}: "지연 트리거가 ... 불균형 분개를 커밋 시 차단한다" — the bundle
 *       contains no trigger on any ledger table at all.
 *   <li>{@code trading.short_borrow_fee_accruals}: "PostgreSQL 마이그레이션은 같은 lot의 비용 기간이
 *       겹치지 않도록 exclusion constraint를 둔다" — the bundle only has a unique index on the exact
 *       triple, which permits overlapping periods and therefore double billing.
 * </ul>
 *
 * <p>Both are deferred, so every assertion here commits rather than merely executing a statement.
 */
@Testcontainers(disabledWithoutDocker = true)
class CanonicalInvariantContributionTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String BOT = "b1000000-0000-4000-8000-000000000001";
    private static final String PARTITION = "c1000000-0000-4000-8000-000000000001";
    private static final String FLOW = "d1000000-0000-4000-8000-000000000001";
    private static final String INSTRUMENT = "e1000000-0000-4000-8000-000000000001";
    private static final String CASH_ACCOUNT = "17100000-0000-4000-8000-000000000001";
    private static final String POSITION_ACCOUNT = "17100000-0000-4000-8000-000000000002";
    private static final String LOT = "1d100000-0000-4000-8000-000000000001";
    private static final String OTHER_LOT = "1d100000-0000-4000-8000-000000000002";
    private static final String BORROW_POLICY = "92100000-0000-4000-8000-000000000001";

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrateCanonicalBaselineAndContributions() throws SQLException {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        seedCanonicalParents();
    }

    @BeforeEach
    void clearContributionSubjects() {
        jdbc.sql("truncate table trading.short_borrow_fee_accruals, trading.ledger_entries, "
                + "trading.ledger_transactions cascade").update();
    }

    @Test
    void theContributedObjectsExist() {
        for (String routine : new String[] {
                "assert_ledger_transaction_balanced",
                "assert_ledger_transaction_source",
                "assert_borrow_fee_period_isolation"}) {
            assertTrue(
                    Boolean.TRUE.equals(jdbc.sql("""
                            select exists (select 1 from pg_proc p
                                             join pg_namespace n on n.oid = p.pronamespace
                                            where n.nspname = 'trading' and p.proname = ?)
                            """).param(routine).query(Boolean.class).single()),
                    "trading." + routine + " was not contributed");
        }
        for (String trigger : new String[] {
                "ledger_transaction_balanced_deferred",
                "ledger_entry_balanced_deferred",
                "borrow_fee_period_isolation_deferred"}) {
            assertTrue(
                    Boolean.TRUE.equals(jdbc.sql(
                            "select exists (select 1 from pg_trigger where tgname = ?)")
                            .param(trigger).query(Boolean.class).single()),
                    trigger + " was not contributed");
        }
    }

    @Test
    void aBalancedPostingCommitsAndKeepsBothSides() throws SQLException {
        commit(statement -> {
            statement.addBatch(ledgerTransaction("18100000-0000-4000-8000-000000000001", "BOT1:FILL:1", 1));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000001",
                    "18100000-0000-4000-8000-000000000001", CASH_ACCOUNT, 1, "CREDIT", "40.10000000"));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000002",
                    "18100000-0000-4000-8000-000000000001", POSITION_ACCOUNT, 2, "DEBIT", "40.10000000"));
        });

        assertEquals(2, count("select count(*) from trading.ledger_entries"));
        assertEquals(
                0,
                jdbc.sql("""
                        select sum(case when direction = 'DEBIT' then amount else -amount end)
                          from trading.ledger_entries
                        """).query(java.math.BigDecimal.class).single().compareTo(java.math.BigDecimal.ZERO));
    }

    @Test
    void anUnbalancedPostingCannotCommit() {
        SQLException failure = assertThrows(SQLException.class, () -> commit(statement -> {
            statement.addBatch(ledgerTransaction("18100000-0000-4000-8000-000000000002", "BOT1:FILL:2", 2));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000003",
                    "18100000-0000-4000-8000-000000000002", CASH_ACCOUNT, 1, "CREDIT", "40.10000000"));
            // One cent of the credit is not matched by any debit.
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000004",
                    "18100000-0000-4000-8000-000000000002", POSITION_ACCOUNT, 2, "DEBIT", "40.09000000"));
        }));

        assertTrue(
                message(failure).contains("is unbalanced by"),
                () -> "unexpected failure: " + message(failure));
        assertEquals(0, count("select count(*) from trading.ledger_entries"));
    }

    @Test
    void aSingleSidedPostingCannotCommit() {
        SQLException failure = assertThrows(SQLException.class, () -> commit(statement -> {
            statement.addBatch(ledgerTransaction("18100000-0000-4000-8000-000000000003", "BOT1:FILL:3", 3));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000005",
                    "18100000-0000-4000-8000-000000000003", CASH_ACCOUNT, 1, "CREDIT", "40.10000000"));
        }));

        assertTrue(
                message(failure).contains("double-entry requires at least two"),
                () -> "unexpected failure: " + message(failure));
    }

    @Test
    void removingOneSideOfACommittedPostingCannotCommitEither() throws SQLException {
        commit(statement -> {
            statement.addBatch(ledgerTransaction("18100000-0000-4000-8000-000000000004", "BOT1:FILL:4", 4));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000006",
                    "18100000-0000-4000-8000-000000000004", CASH_ACCOUNT, 1, "CREDIT", "12.00000000"));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000007",
                    "18100000-0000-4000-8000-000000000004", POSITION_ACCOUNT, 2, "DEBIT", "12.00000000"));
        });

        SQLException failure = assertThrows(SQLException.class, () -> commit(statement ->
                statement.addBatch("delete from trading.ledger_entries "
                        + "where id = '19100000-0000-4000-8000-000000000007'")));

        // Removing one of exactly two entries trips the entry-count rule before the balance rule;
        // either way the posting cannot be left half-recorded.
        assertTrue(
                message(failure).contains("double-entry requires at least two"),
                () -> "unexpected failure: " + message(failure));
        assertEquals(2, count("select count(*) from trading.ledger_entries"));
    }

    @Test
    void aPostingThatClaimsAFillWhichDoesNotExistCannotCommit() {
        SQLException failure = assertThrows(SQLException.class, () -> commit(statement -> {
            statement.addBatch("""
                    insert into trading.ledger_transactions (
                        id, bot_id, partition_id, bot_event_id, transaction_type, transaction_key,
                        source_type, source_id, currency_code, occurred_at, description_code)
                    values ('18100000-0000-4000-8000-000000000005', '%s', '%s',
                        '95100000-0000-4000-8000-000000000005', 'FILL_SETTLEMENT', 'BOT1:FILL:5',
                        'FILL', 'ffffffff-0000-4000-8000-00000000ffff', 'USD',
                        '2026-07-28T09:00:00+00', 'BUY_FILL_SETTLED')
                    """.formatted(BOT, PARTITION));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000008",
                    "18100000-0000-4000-8000-000000000005", CASH_ACCOUNT, 1, "CREDIT", "5.00000000"));
            statement.addBatch(ledgerEntry("19100000-0000-4000-8000-000000000009",
                    "18100000-0000-4000-8000-000000000005", POSITION_ACCOUNT, 2, "DEBIT", "5.00000000"));
        }));

        assertTrue(
                message(failure).contains("which does not exist for this bot"),
                () -> "unexpected failure: " + message(failure));
    }

    @Test
    void overlappingBorrowFeePeriodsOnOneLotCannotCommit() throws SQLException {
        commit(statement -> borrowFeeAccrual(statement, 1, LOT,
                "2026-07-28T00:00:00+00", "2026-07-29T00:00:00+00"));

        SQLException failure = assertThrows(SQLException.class, () -> commit(statement ->
                borrowFeeAccrual(statement, 2, LOT,
                        "2026-07-28T12:00:00+00", "2026-07-29T12:00:00+00")));

        assertTrue(
                message(failure).contains("overlaps accrual"),
                () -> "unexpected failure: " + message(failure));
        assertEquals(1, count("select count(*) from trading.short_borrow_fee_accruals"));
    }

    @Test
    void adjacentBorrowFeePeriodsAndOtherLotsStillCommit() throws SQLException {
        commit(statement -> borrowFeeAccrual(statement, 3, LOT,
                "2026-07-28T00:00:00+00", "2026-07-29T00:00:00+00"));
        // The end bound is exclusive, so a period starting exactly where the previous one ended is
        // the normal daily accrual and must not be rejected.
        commit(statement -> borrowFeeAccrual(statement, 4, LOT,
                "2026-07-29T00:00:00+00", "2026-07-30T00:00:00+00"));
        // The same period on a different lot is a different short position.
        commit(statement -> borrowFeeAccrual(statement, 5, OTHER_LOT,
                "2026-07-28T00:00:00+00", "2026-07-29T00:00:00+00"));

        assertEquals(3, count("select count(*) from trading.short_borrow_fee_accruals"));
    }

    private static void borrowFeeAccrual(
            Statement statement, int ordinal, String lotId, String start, String end)
            throws SQLException {
        String transactionId = "18200000-0000-4000-8000-0000000000%02d".formatted(ordinal);
        String eventId = "95200000-0000-4000-8000-0000000000%02d".formatted(ordinal);
        statement.addBatch("""
                insert into trading.ledger_transactions (
                    id, bot_id, partition_id, bot_event_id, transaction_type, transaction_key,
                    source_type, source_id, currency_code, occurred_at, description_code)
                values ('%s', '%s', '%s', '%s', 'SHORT_BORROW_FEE', 'BOT1:BORROW:%d',
                    'SHORT_BORROW_FEE_ACCRUAL', '%s', 'USD', '%s', 'BORROW_FEE_ACCRUED')
                """.formatted(transactionId, BOT, PARTITION, eventId, ordinal, transactionId, start));
        statement.addBatch(ledgerEntry(
                "19200000-0000-4000-8000-0000000001%02d".formatted(ordinal),
                transactionId, CASH_ACCOUNT, 1, "CREDIT", "0.05000000"));
        statement.addBatch(ledgerEntry(
                "19200000-0000-4000-8000-0000000002%02d".formatted(ordinal),
                transactionId, POSITION_ACCOUNT, 2, "DEBIT", "0.05000000"));
        statement.addBatch("""
                insert into trading.short_borrow_fee_accruals (
                    id, bot_id, partition_id, position_lot_id, bot_event_id,
                    short_borrow_fee_policy_id, ledger_transaction_id, period_start, period_end,
                    annual_fee_rate_bps, day_count_basis, fee_basis_amount, accrued_fee_amount,
                    calculation_hash)
                values ('1e200000-0000-4000-8000-0000000000%02d', '%s', '%s', '%s', '%s', '%s',
                    '%s', '%s', '%s', 25.000000, 'ACT/365', 100.00000000, 0.05000000, '%s')
                """.formatted(ordinal, BOT, PARTITION, lotId, eventId, BORROW_POLICY,
                transactionId, start, end, "a".repeat(64)));
    }

    /** Bot event ids are seeded up front; every posting consumes a distinct one. */
    private static String botEvent(int ordinal) {
        return "95100000-0000-4000-8000-0000000000%02d".formatted(ordinal);
    }

    private static String ledgerTransaction(String id, String key, int eventOrdinal) {
        return """
                insert into trading.ledger_transactions (
                    id, bot_id, partition_id, bot_event_id, transaction_type, transaction_key,
                    source_type, source_id, currency_code, occurred_at, description_code)
                values ('%s', '%s', '%s', '%s', 'INITIAL_CAPITAL', '%s', 'INITIAL_CAPITAL', '%s',
                    'USD', '2026-07-28T09:00:00+00', 'CAPITAL_DEPOSITED')
                """.formatted(id, BOT, PARTITION, botEvent(eventOrdinal), key, id);
    }

    private static String ledgerEntry(
            String id, String transactionId, String accountId, int sequence,
            String direction, String amount) {
        return """
                insert into trading.ledger_entries (
                    id, bot_id, partition_id, transaction_id, ledger_account_id, entry_sequence,
                    direction, amount, entry_hash)
                values ('%s', '%s', '%s', '%s', '%s', %d, '%s', %s, '%s')
                """.formatted(id, BOT, PARTITION, transactionId, accountId, sequence,
                direction, amount, "b".repeat(64));
    }

    private interface Work {
        void run(Statement statement) throws SQLException;
    }

    /** Runs the work in one explicit transaction so deferred triggers fire on commit. */
    private static void commit(Work work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                work.run(statement);
                statement.executeBatch();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String message(SQLException failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
            if (current instanceof SQLException sql && sql.getNextException() != null) {
                text.append(sql.getNextException().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    /**
     * Parents outside the focused aggregate are bypassed exactly as the canonical contract fixtures
     * do; every assertion above then runs with normal triggers. This must run on one connection,
     * because {@code session_replication_role} is session state and the data source hands out a new
     * connection per statement.
     */
    private static void seedCanonicalParents() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");

            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a1000000-0000-4000-8000-000000000001', 'BASIC',
                        'Canonical invariant bot', 'RUNNING', '2026-07-28T09:00:00+00',
                        '2026-07-28T09:00:00+00', '2026-07-28T09:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps, position_x,
                        position_y, configuration_hash)
                    values ('%s', '%s', 'Partition one', 10000, 0, 0, repeat('a', 64))
                    """.formatted(PARTITION, BOT));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow one', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', '1.0.0', repeat('a', 64), repeat('b', 64), repeat('a', 64))
                    """.formatted(FLOW, PARTITION));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic)
                    values ('%s', 'STOCK', 'XNAS')
                    """.formatted(INSTRUMENT));

            // Every canonical trading write is caused by a bot event, so one is seeded per posting.
            for (int ordinal = 1; ordinal <= 20; ordinal++) {
                statement.addBatch(botEventSeed("95100000", ordinal, ordinal));
                statement.addBatch(botEventSeed("95200000", ordinal, 100 + ordinal));
            }

            statement.addBatch("""
                    insert into trading.ledger_accounts (id, bot_id, account_key, partition_id,
                        account_type, currency_code, created_at)
                    values ('%s', '%s', 'BOT1:P1:CASH:USD', '%s', 'CASH', 'USD',
                        '2026-07-28T09:00:00+00')
                    """.formatted(CASH_ACCOUNT, BOT, PARTITION));
            statement.addBatch("""
                    insert into trading.ledger_accounts (id, bot_id, account_key, partition_id,
                        account_type, instrument_id, created_at)
                    values ('%s', '%s', 'BOT1:P1:POSITION:INSTR1', '%s', 'POSITION', '%s',
                        '2026-07-28T09:00:00+00')
                    """.formatted(POSITION_ACCOUNT, BOT, PARTITION, INSTRUMENT));
            statement.addBatch("""
                    insert into trading.short_borrow_fee_policy_versions (id, policy_code, version,
                        annual_fee_rate_bps, day_count_basis, calculation_rules_version, rules_hash,
                        effective_from, published_at)
                    values ('%s', 'OFFICIAL_BORROW_FEE', '1.0.0', 25.000000, 'ACT/365', '1.0.0',
                        repeat('c', 64), '2026-07-28T09:00:00+00', '2026-07-28T09:00:00+00')
                    """.formatted(BORROW_POLICY));
            for (String lot : new String[] {LOT, OTHER_LOT}) {
                statement.addBatch("""
                        insert into trading.position_lots (id, bot_id, partition_id, flow_id,
                            instrument_id, opening_order_component_id, opening_fill_allocation_id,
                            lot_side, opened_quantity, unit_cost, opened_cost_basis_amount,
                            opened_at)
                        values ('%s', '%s', '%s', '%s', '%s', gen_random_uuid(), gen_random_uuid(),
                            'SHORT', 10.00000000, 10.00000000, 100.00000000,
                            '2026-07-28T09:00:00+00')
                        """.formatted(lot, BOT, PARTITION, FLOW, INSTRUMENT));
            }

            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    private static String botEventSeed(String prefix, int ordinal, int sequence) {
        return """
                insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                    event_schema_version, correlation_id, idempotency_key, occurred_at,
                    received_at, summary_document)
                values ('%s-0000-4000-8000-0000000000%02d', '%s', %d, 'CANONICAL_INVARIANT_PROBE',
                    '1.0.0', gen_random_uuid(), '%s:%02d', '2026-07-28T09:00:00+00',
                    '2026-07-28T09:00:00+00', '{}')
                """.formatted(prefix, ordinal, BOT, sequence, prefix, ordinal);
    }
}
