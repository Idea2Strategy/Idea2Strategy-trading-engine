package com.idea2strategy.trading.persistence.budget;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.budget.BudgetProjectionConflictException;
import com.idea2strategy.trading.application.budget.BudgetProjectionOutcome;
import com.idea2strategy.trading.application.budget.BudgetProjectionResult;
import com.idea2strategy.trading.application.budget.BudgetProjectionService;
import com.idea2strategy.trading.application.budget.BudgetRebuildResult;
import com.idea2strategy.trading.domain.budget.BotBudgetProjection;
import com.idea2strategy.trading.domain.budget.BudgetProjectionRebuild;
import com.idea2strategy.trading.domain.budget.PartitionBudgetProjection;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the budget projection write path against the real canonical schema.
 *
 * <p>These two tables are new storage rather than a migration: the private schema had no budget
 * table at all. They are also rebuildable read models whose amounts come from the ledger, the
 * active reservations and the current valuation, so this test never computes a balance. It seeds
 * only the {@code bot.*} parents the foreign keys need and then checks the two things the write
 * path is actually responsible for: that a supplied projection lands in the canonical shape, and
 * that {@code last_event_sequence} plus {@code projection_hash} order and deduplicate the rebuilds
 * that produce it.
 */
@Testcontainers(disabledWithoutDocker = true)
class BudgetProjectionPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION_A = UUID.fromString("31000000-0000-4000-8000-00000000000a");
    private static final UUID PARTITION_B = UUID.fromString("31000000-0000-4000-8000-00000000000b");
    private static final UUID OTHER_BOT = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_PARTITION =
            UUID.fromString("31000000-0000-4000-8000-00000000000c");

    private static final Instant VALUED_AT = Instant.parse("2026-08-02T14:30:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static BudgetProjectionService service;
    private static JooqBudgetProjectionQuery query;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        service = new BudgetProjectionService(newStore());
        query = new JooqBudgetProjectionQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        // bot.* belongs to another service; seeded with referential triggers off exactly as the
        // canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at,
                        created_at)
                    values ('%s', 'ACTIVE', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(ACCOUNT));
            statement.addBatch(bot(BOT, "Budget bot"));
            statement.addBatch(bot(OTHER_BOT, "Other bot"));
            statement.addBatch(partitionRow(PARTITION_A, BOT, "Partition A", 6000));
            statement.addBatch(partitionRow(PARTITION_B, BOT, "Partition B", 4000));
            statement.addBatch(partitionRow(OTHER_PARTITION, OTHER_BOT, "Other partition", 10000));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    @BeforeEach
    void clearProjections() {
        jdbc.sql("delete from trading.partition_budget_projections").update();
        jdbc.sql("delete from trading.bot_budget_projections").update();
    }

    @Test
    void aRebuildLandsBothCanonicalRowsInTheShapeTheColumnsDeclare() {
        BudgetRebuildResult result = service.rebuild(rebuild(7, "1000", "600", "400"));

        var stored = query.findBot(BOT).orElseThrow();
        var partitions = query.findPartitionsOfBot(BOT);

        assertAll(
                () -> assertEquals(BudgetProjectionOutcome.CREATED, result.bot().outcome()),
                () -> assertEquals(2, result.partitions().size()),
                () -> assertEquals("USD", stored.currencyCode()),
                () -> assertDecimal("1000", stored.availableCashAmount()),
                () -> assertDecimal("50", stored.activeReservationAmount()),
                () -> assertDecimal("200", stored.investedAmount()),
                () -> assertDecimal("30", stored.segregatedShortProceedsAmount()),
                () -> assertDecimal("15", stored.shortCollateralAmount()),
                () -> assertEquals(VALUED_AT, stored.valuationAt()),
                () -> assertEquals("VALUED", stored.valuationStatus()),
                () -> assertEquals(7, stored.lastEventSequence()),
                () -> assertEquals(64, stored.projectionHash().length()),
                () -> assertEquals(result.bot().projectionHash(), stored.projectionHash()),
                () -> assertEquals(List.of(PARTITION_A, PARTITION_B),
                        partitions.stream().map(p -> p.partitionId()).toList()),
                () -> assertTrue(partitions.stream().allMatch(p -> BOT.equals(p.botId())),
                        "canonical keeps the owning bot on the partition row"),
                () -> assertDecimal("600", partitions.getFirst().budgetCapAmount()),
                () -> assertDecimal("400", partitions.get(1).budgetCapAmount()),
                () -> assertEquals(7, partitions.getFirst().lastEventSequence()));
    }

    /**
     * The projection hash is what replaces a private receipt table: a rebuild of the same history
     * at the same event sequence is recognised rather than rewritten.
     */
    @Test
    void aRebuildDeliveredTwiceAtTheSameEventSequenceWritesNothingTheSecondTime() {
        BudgetRebuildResult first = service.rebuild(rebuild(7, "1000", "600", "400"));
        Instant writtenAt = query.findBot(BOT).orElseThrow().updatedAt();

        BudgetRebuildResult second =
                new BudgetProjectionService(newStore()).rebuild(rebuild(7, "1000", "600", "400"));

        assertAll(
                () -> assertTrue(second.replayed()),
                () -> assertEquals(first.bot().projectionHash(), second.bot().projectionHash()),
                () -> assertEquals(writtenAt, query.findBot(BOT).orElseThrow().updatedAt(),
                        "an unchanged projection is not rewritten"),
                () -> assertEquals(1, query.countBotProjections()),
                () -> assertEquals(2, query.countPartitionProjections()));
    }

    /**
     * A rebuild of the same history cannot legitimately produce two answers, so the same event
     * sequence carrying different amounts is a conflict rather than the newer truth.
     */
    @Test
    void aDifferentAnswerAtTheSameEventSequenceIsRefused() {
        service.rebuild(rebuild(7, "1000", "600", "400"));

        BudgetProjectionConflictException failure = assertThrows(
                BudgetProjectionConflictException.class,
                () -> service.rebuild(rebuild(7, "1200", "600", "400")));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("event sequence 7")),
                () -> assertDecimal("1000",
                        query.findBot(BOT).orElseThrow().availableCashAmount()));
    }

    @Test
    void aLaterEventSequenceAdvancesTheStoredProjection() {
        service.rebuild(rebuild(7, "1000", "600", "400"));

        BudgetRebuildResult advanced = service.rebuild(rebuild(9, "1200", "600", "400"));

        var stored = query.findBot(BOT).orElseThrow();
        assertAll(
                () -> assertEquals(BudgetProjectionOutcome.ADVANCED, advanced.bot().outcome()),
                () -> assertDecimal("1200", stored.availableCashAmount()),
                () -> assertEquals(9, stored.lastEventSequence()),
                () -> assertEquals(9, query.findPartition(PARTITION_A).orElseThrow()
                        .lastEventSequence()),
                () -> assertEquals(1, query.countBotProjections()));
    }

    /** A slow rebuild must not walk a projection backwards onto a history it has already left. */
    @Test
    void anEarlierEventSequenceIsRefusedAsStale() {
        service.rebuild(rebuild(9, "1200", "600", "400"));

        BudgetProjectionConflictException failure = assertThrows(
                BudgetProjectionConflictException.class,
                () -> service.rebuild(rebuild(7, "1000", "600", "400")));

        var stored = query.findBot(BOT).orElseThrow();
        assertAll(
                () -> assertTrue(failure.getMessage().contains("stale")),
                () -> assertEquals(9, stored.lastEventSequence()),
                () -> assertDecimal("1200", stored.availableCashAmount()));
    }

    /**
     * Canonical states no relationship between a bot row and its partition rows, so a half-applied
     * rebuild would be indistinguishable from a coherent one. The transaction is what prevents it.
     */
    @Test
    void aStalePartitionRollsBackTheWholeRebuildIncludingTheBotRow() {
        service.rebuild(rebuild(7, "1000", "600", "400"));
        // Only partition B moves on, which leaves the next whole-bot rebuild at 8 stale for B alone.
        service.project(partition(PARTITION_B, "400", 12));

        BudgetProjectionConflictException failure = assertThrows(
                BudgetProjectionConflictException.class,
                () -> service.rebuild(rebuild(8, "1500", "600", "400")));

        var storedBot = query.findBot(BOT).orElseThrow();
        var storedA = query.findPartition(PARTITION_A).orElseThrow();
        assertAll(
                () -> assertTrue(failure.getMessage().contains("stale")),
                () -> assertEquals(7, storedBot.lastEventSequence(),
                        "the bot row written earlier in the rebuild rolled back"),
                () -> assertDecimal("1000", storedBot.availableCashAmount()),
                () -> assertEquals(7, storedA.lastEventSequence(),
                        "the partition written earlier in the rebuild rolled back"),
                () -> assertEquals(12,
                        query.findPartition(PARTITION_B).orElseThrow().lastEventSequence()));
    }

    /** Canonical points the partition row at {@code bot_partitions (bot_id, id)}. */
    @Test
    void aPartitionBudgetCannotBeAttributedToABotThatDoesNotOwnThePartition() {
        BudgetProjectionConflictException failure = assertThrows(
                BudgetProjectionConflictException.class,
                () -> service.project(new PartitionBudgetProjection(
                        OTHER_PARTITION, BOT, "USD", new BigDecimal("100"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, VALUED_AT, "VALUED", 7)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("not owned by the bot")),
                () -> assertEquals(0, query.countPartitionProjections()));
    }

    /**
     * The write path refuses negative amounts before they reach a statement, but the guarantee is
     * canonical's, not the application's. Written straight to the tables, the database still says
     * no — so a row that skipped this store cannot hold a negative budget either.
     */
    @Test
    void canonicalItselfRefusesNegativeAmountsAndANonPositiveCap() {
        assertAll(
                () -> assertViolates("bot_budget_available_nonnegative",
                        () -> insertBotRow("-1", "0", "0", "0", "0")),
                () -> assertViolates("bot_budget_reservation_nonnegative",
                        () -> insertBotRow("0", "-1", "0", "0", "0")),
                () -> assertViolates("bot_budget_invested_nonnegative",
                        () -> insertBotRow("0", "0", "-1", "0", "0")),
                () -> assertViolates("bot_budget_short_proceeds_nonnegative",
                        () -> insertBotRow("0", "0", "0", "-1", "0")),
                () -> assertViolates("bot_budget_short_collateral_nonnegative",
                        () -> insertBotRow("0", "0", "0", "0", "-1")),
                () -> assertViolates("partition_budget_reservation_nonnegative",
                        () -> insertPartitionRow("100", "-1", "0", "0", "0")),
                () -> assertViolates("partition_budget_invested_nonnegative",
                        () -> insertPartitionRow("100", "0", "-1", "0", "0")),
                () -> assertViolates("partition_budget_short_proceeds_nonnegative",
                        () -> insertPartitionRow("100", "0", "0", "-1", "0")),
                () -> assertViolates("partition_budget_short_collateral_nonnegative",
                        () -> insertPartitionRow("100", "0", "0", "0", "-1")),
                () -> assertViolates("partition_budget_cap_positive",
                        () -> insertPartitionRow("0", "0", "0", "0", "0")),
                () -> assertEquals(0, query.countBotProjections()),
                () -> assertEquals(0, query.countPartitionProjections()));
    }

    @Test
    void aBudgetProjectionCannotNameABotThatDoesNotExist() {
        BudgetProjectionConflictException failure = assertThrows(
                BudgetProjectionConflictException.class,
                () -> service.project(new BotBudgetProjection(
                        UUID.fromString("30000000-0000-4000-8000-0000000000ff"), "USD",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, VALUED_AT, "VALUED", 7)));

        assertTrue(failure.getMessage().contains("no such bot"));
    }

    /** A single row may be projected on its own; the rebuild is a convenience over the same rule. */
    @Test
    void oneRowMayBeProjectedWithoutTheRestOfTheBot() {
        BudgetProjectionResult result = service.project(partition(PARTITION_A, "600", 4));

        assertAll(
                () -> assertEquals(BudgetProjectionOutcome.CREATED, result.outcome()),
                () -> assertEquals(0, query.countBotProjections()),
                () -> assertEquals(1, query.countPartitionProjections()),
                () -> assertEquals(4,
                        query.findPartition(PARTITION_A).orElseThrow().lastEventSequence()));
    }

    private static void assertViolates(String constraint, Runnable write) {
        DataIntegrityViolationException failure =
                assertThrows(DataIntegrityViolationException.class, write::run);
        assertTrue(failure.getMessage().contains(constraint),
                () -> "expected " + constraint + " but was " + failure.getMessage());
    }

    private static void insertBotRow(
            String available, String reservation, String invested, String proceeds,
            String collateral) {
        jdbc.sql("""
                        insert into trading.bot_budget_projections (
                            bot_id, currency_code, available_cash_amount,
                            active_reservation_amount, invested_amount,
                            segregated_short_proceeds_amount, short_collateral_amount,
                            valuation_at, valuation_status, last_event_sequence, projection_hash,
                            updated_at
                        ) values (
                            :botId, 'USD', :available, :reservation, :invested, :proceeds,
                            :collateral, :valuationAt, 'VALUED', 1, 'x', current_timestamp
                        )
                        """)
                .param("botId", BOT)
                .param("available", new BigDecimal(available))
                .param("reservation", new BigDecimal(reservation))
                .param("invested", new BigDecimal(invested))
                .param("proceeds", new BigDecimal(proceeds))
                .param("collateral", new BigDecimal(collateral))
                .param("valuationAt", VALUED_AT.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private static void insertPartitionRow(
            String cap, String reservation, String invested, String proceeds, String collateral) {
        jdbc.sql("""
                        insert into trading.partition_budget_projections (
                            partition_id, bot_id, currency_code, budget_cap_amount,
                            active_reservation_amount, invested_amount,
                            segregated_short_proceeds_amount, short_collateral_amount,
                            valuation_at, valuation_status, last_event_sequence, projection_hash,
                            updated_at
                        ) values (
                            :partitionId, :botId, 'USD', :cap, :reservation, :invested, :proceeds,
                            :collateral, :valuationAt, 'VALUED', 1, 'x', current_timestamp
                        )
                        """)
                .param("partitionId", PARTITION_A)
                .param("botId", BOT)
                .param("cap", new BigDecimal(cap))
                .param("reservation", new BigDecimal(reservation))
                .param("invested", new BigDecimal(invested))
                .param("proceeds", new BigDecimal(proceeds))
                .param("collateral", new BigDecimal(collateral))
                .param("valuationAt", VALUED_AT.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private static BudgetProjectionRebuild rebuild(
            long sequence, String availableCash, String capA, String capB) {
        return new BudgetProjectionRebuild(
                new BotBudgetProjection(
                        BOT, "USD", new BigDecimal(availableCash), new BigDecimal("50"),
                        new BigDecimal("200"), new BigDecimal("30"), new BigDecimal("15"),
                        VALUED_AT, "VALUED", sequence),
                List.of(partition(PARTITION_A, capA, sequence),
                        partition(PARTITION_B, capB, sequence)));
    }

    private static PartitionBudgetProjection partition(UUID id, String cap, long sequence) {
        return new PartitionBudgetProjection(
                id, BOT, "USD", new BigDecimal(cap), new BigDecimal("25"), new BigDecimal("100"),
                new BigDecimal("15"), new BigDecimal("5"), VALUED_AT, "VALUED", sequence);
    }

    private static String bot(UUID id, String name) {
        return """
                insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                    lifecycle_changed_at, created_at, execution_eligible_from)
                values ('%s', '%s', 'BASIC', '%s', 'RUNNING', '2026-08-01T00:00:00+00',
                    '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                """.formatted(id, ACCOUNT, name);
    }

    private static String partitionRow(UUID id, UUID botId, String name, int capBps) {
        return """
                insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps, position_x,
                    position_y, configuration_hash)
                values ('%s', '%s', '%s', %d, 0, 0, '%s')
                """.formatted(id, botId, name, capBps, "c".repeat(64));
    }

    private static PostgresBudgetProjectionStore newStore() {
        return new PostgresBudgetProjectionStore(JdbcClient.create(dataSource), transactions);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
