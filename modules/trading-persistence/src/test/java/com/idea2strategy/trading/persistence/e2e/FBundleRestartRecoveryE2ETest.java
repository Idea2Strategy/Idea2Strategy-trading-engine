package com.idea2strategy.trading.persistence.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.budget.BudgetProjectionService;
import com.idea2strategy.trading.application.fill.FillRecordService;
import com.idea2strategy.trading.application.ledger.LedgerPostingService;
import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleService;
import com.idea2strategy.trading.application.position.PositionLotService;
import com.idea2strategy.trading.application.reservation.ConsumeReservationCommand;
import com.idea2strategy.trading.application.reservation.ResourceReservationService;
import com.idea2strategy.trading.application.reservation.SettleReservationCommand;
import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.application.stop.RequestBotStopCommand;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.budget.BotBudgetProjection;
import com.idea2strategy.trading.application.budget.BudgetRebuildResult;
import com.idea2strategy.trading.domain.budget.BudgetProjectionRebuild;
import com.idea2strategy.trading.domain.budget.PartitionBudgetProjection;
import com.idea2strategy.trading.domain.fill.FillAllocation;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.ledger.LedgerEntryDraft;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.position.LotOpening;
import com.idea2strategy.trading.domain.position.LotSide;
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.persistence.budget.PostgresBudgetProjectionStore;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.event.PostgresBotEventStore;
import com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore;
import com.idea2strategy.trading.persistence.ledger.PostgresLedgerStore;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import com.idea2strategy.trading.persistence.position.PostgresPositionLotStore;
import com.idea2strategy.trading.persistence.reservation.PostgresResourceReservationStore;
import com.idea2strategy.trading.persistence.stop.PostgresBotStopSettlementStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * The F bundle end to end, and what a restart is allowed to change about it.
 *
 * <p>One approved intent is carried through budget, reservation, order, two partial fills, the
 * official ledger, position lots and finally a forced stop, against the real canonical schema. The
 * point of the test is not that the sequence runs — the per-path tests already prove that — but that
 * the canonical state it leaves is a function of the inputs alone.
 *
 * <p>Two things are therefore allowed to vary and must not change the outcome. A <em>restart</em> is
 * modelled by building every store again between steps, so nothing carried in memory can be what
 * makes the next step correct. A <em>redelivery</em> is modelled by submitting each step twice with
 * the identical command, which is what an at-least-once queue does after a crash between the work
 * and the acknowledgement.
 *
 * <p>The comparison is a snapshot of the canonical tables rather than a list of assertions per
 * table, because the interesting failure is a row that appears twice or a total that drifts, and
 * naming the columns to check in advance is exactly how that gets missed. Surrogate keys the
 * database generates are excluded; every other identifier in this schema is derived from the meaning
 * of the row, which is what makes the two runs comparable at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class FBundleRestartRecoveryE2ETest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-00000000000f");
    private static final UUID BOT = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("f4000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("f6000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("f7000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY = UUID.fromString("f7000000-0000-4000-8000-000000000002");
    private static final UUID INTENT_BATCH = UUID.fromString("f8000000-0000-4000-8000-000000000001");
    private static final UUID INTENT = UUID.fromString("f9000000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE = UUID.fromString("fa000000-0000-4000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-08-03T14:30:00Z");
    private static final String PRECISION = "precision-rules:v1";
    private static final String MARKET_HASH = "e".repeat(64);

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins ORDER_PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", PRECISION, "order-intent-composition:v1");
    private static final ReservationPolicyPins CASH_PINS =
            ReservationPolicyPins.buyingPower(BUFFER_POLICY, FEE_POLICY, PRECISION);

    /** The whole order, and the two partial fills it is filled by. */
    private static final BigDecimal QUANTITY = decimal("3");
    private static final BigDecimal PRICE = decimal("10");
    private static final BigDecimal FIRST_FILL = decimal("1");
    private static final BigDecimal SECOND_FILL = decimal("2");

    /** base notional 30 + slippage 0.02 + fee 0.06 + buffer 0.92, which is what cash() is given. */
    private static final BigDecimal RESERVED = decimal("31.00");

    private static final int EVENT_POOL = 12;
    private static final List<UUID> EVENTS = new ArrayList<>();

    /**
     * The canonical tables the F bundle writes, in dependency order. Everything here is compared
     * between the two runs.
     */
    private static final List<String> CANONICAL_TABLES = List.of(
            "order_intent_batches",
            "order_intents",
            "orders",
            "order_components",
            "order_events",
            "order_state_projections",
            "resource_reservations",
            "reservation_events",
            "order_component_reservations",
            "position_lot_reservations",
            "fills",
            "fill_component_allocations",
            "fill_adjustments",
            "ledger_accounts",
            "ledger_transactions",
            "ledger_entries",
            "position_lots",
            "lot_movements",
            "position_lot_projections",
            "flow_position_projections",
            "partition_position_projections",
            "bot_budget_projections",
            "partition_budget_projections",
            "system_close_actions");

    /**
     * Columns the database fills in on its own. A surrogate key or an insertion timestamp differing
     * between two runs is not the drift this test is looking for.
     */
    private static final List<String> GENERATED_COLUMNS =
            List.of("id", "created_at", "updated_at", "recorded_at");

    /**
     * Every UUID is masked before two runs are compared, and it is worth being exact about why,
     * because the obvious rule does not hold.
     *
     * <p>Canonical derives the identifiers that carry meaning: an order from its intent, a
     * reservation from its intent and resource, a lot from the fill allocation that opened it. Those
     * are version 5 UUIDs and look reproducible. But the chain bottoms out in identifiers PostgreSQL
     * generates — {@code order_components.id} and {@code fill_component_allocations.id} are
     * {@code gen_random_uuid()} — so a lot id is a stable function of a value that is itself random.
     * It repeats under redelivery, which is what idempotency needs, and does not repeat across two
     * separate runs, which is not a defect.
     *
     * <p>What survives the mask is what this test is actually about: every amount, quantity, status,
     * sequence and hash, and how many rows each table holds. A redelivery that wrote a second lot or
     * a restart that re-consumed a reservation changes those. Referential correctness is not left
     * unchecked — it is the job of the deferred canonical triggers, which pass or the run does not
     * commit at all, and of the per-path persistence tests.
     */
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("f3000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services; seeded with referential triggers off
        // exactly as the canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('%s', 'ACTIVE', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(ACCOUNT));
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', '%s', 'BASIC', 'F bundle bot', 'RUNNING',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(BOT, ACCOUNT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(FLOW, PARTITION, "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            for (int index = 0; index < EVENT_POOL; index++) {
                statement.addBatch("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values ('%s', '%s', %d, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                            'f16-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
                        """.formatted(EVENTS.get(index), BOT, index + 1, index));
            }
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(EVALUATION, BOT, PARTITION, FLOW, EVENTS.getFirst()));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }

        jdbc.sql("""
                insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                    calculation_rules_version, rules_hash, effective_from, published_at)
                values (:id, 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", FEE_POLICY).param("hash", "f".repeat(64)).update();
        jdbc.sql("""
                insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                    buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                values (:id, 'BUYING_POWER_BUFFER', 'v1', 75, 'rounding:v1', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", BUFFER_POLICY).param("hash", "b".repeat(64)).update();
    }

    @BeforeEach
    void clearTradingRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            for (String table : CANONICAL_TABLES.reversed()) {
                statement.addBatch("delete from trading." + table);
            }
            statement.executeBatch();
            connection.commit();
        }
    }

    /**
     * The baseline: one uninterrupted process, each step delivered once. Everything the bundle is
     * supposed to produce has to be here, or the comparison below would be comparing two empties.
     */
    @Test
    void theBundleRunsTheWholeChainAgainstTheCanonicalSchema() {
        BotStopSettlement settlement = run(Delivery.ONCE, Restart.NEVER);

        assertAll(
                () -> assertEquals(StopCheckpoint.STOPPED, settlement.checkpoint()),
                () -> assertEquals(1, count("orders")),
                () -> assertEquals(2, count("fills"), "two partial fills"),
                () -> assertEquals(2, count("position_lots"), "one lot per fill allocation"),
                () -> assertEquals(1, count("resource_reservations")),
                () -> assertEquals(3, count("reservation_events"), "created, consumed, settled"),
                () -> assertEquals(2, count("ledger_transactions")),
                () -> assertEquals(4, count("ledger_entries"), "every posting balances"),
                () -> assertEquals(1, count("bot_budget_projections")),
                () -> assertEquals("SETTLED", one(
                        "select cast(status as varchar) from trading.resource_reservations")),
                () -> assertEquals("FILLED", one(
                        "select cast(status as varchar) from trading.order_state_projections")));
    }

    /**
     * The property the issue asks for: a process that dies between every step and re-receives every
     * message it had already handled ends at the same official state as one that did neither.
     */
    @Test
    void restartAndRedeliveryLeaveTheSameOfficialState() {
        run(Delivery.ONCE, Restart.NEVER);
        String uninterrupted = snapshot();

        clear();
        BotStopSettlement recovered = run(Delivery.TWICE, Restart.BETWEEN_EVERY_STEP);
        String afterRecovery = snapshot();

        assertAll(
                () -> assertEquals(uninterrupted, afterRecovery),
                () -> assertEquals(StopCheckpoint.STOPPED, recovered.checkpoint()));
    }

    /**
     * Guards the guard. A snapshot blind to the rows the chain writes would make the comparison
     * above pass whatever happened, so a single changed amount has to be visible to it.
     *
     * <p>The drift is applied with SQL rather than through a write path, because every write path
     * here correctly refuses to produce it — which is the point of them, and no way to test this.
     */
    @Test
    void theSnapshotNoticesADriftedAmount() {
        run(Delivery.ONCE, Restart.NEVER);
        String expected = snapshot();
        assertTrue(expected.contains("bot_budget_projections"), "the snapshot covers the projection");

        jdbc.sql("update trading.bot_budget_projections set available_cash_amount = 1").update();

        assertNotEquals(expected, snapshot());
    }

    // ------------------------------------------------------------------ the chain

    private enum Delivery { ONCE, TWICE }

    private enum Restart { NEVER, BETWEEN_EVERY_STEP }

    /**
     * Runs intent to stop. {@code delivery} decides whether every step is submitted a second time,
     * and {@code restart} whether the stores are rebuilt before each one.
     */
    private BotStopSettlement run(Delivery delivery, Restart restart) {
        Process process = new Process();
        seedApprovedIntent();

        step(delivery, restart, process, p -> reserve(p));
        UUID orderId = step(delivery, restart, process, p -> placeOrder(p));
        UUID componentId = componentOf(orderId);
        step(delivery, restart, process, p -> attach(p, componentId));

        // First partial fill: the reservation is drawn on but stays active.
        UUID firstFill = step(delivery, restart, process,
                p -> fill(p, 1, FIRST_FILL, EVENTS.get(3), T0.plusSeconds(1)));
        step(delivery, restart, process, p -> consume(p, firstFill, cashOf(FIRST_FILL), 1));
        step(delivery, restart, process, p -> openLot(p, firstFill, FIRST_FILL, T0.plusSeconds(1)));
        step(delivery, restart, process, p -> post(p, EVENTS.get(4), grossOf(FIRST_FILL)));

        // Second partial fill completes the order, which is what settles the reservation.
        UUID secondFill = step(delivery, restart, process,
                p -> fill(p, 2, SECOND_FILL, EVENTS.get(5), T0.plusSeconds(2)));
        step(delivery, restart, process, p -> settle(p, secondFill, cashOf(SECOND_FILL), 2));
        step(delivery, restart, process, p -> openLot(p, secondFill, SECOND_FILL, T0.plusSeconds(2)));
        step(delivery, restart, process, p -> post(p, EVENTS.get(6), grossOf(SECOND_FILL)));

        step(delivery, restart, process, p -> rebuildBudget(p));
        return step(delivery, restart, process, p -> stop(p));
    }

    /**
     * One step of the chain. A redelivery is the same command again, not a similar one: the whole
     * point is that the second submission carries no new information.
     *
     * <p>The two results are deliberately not compared. Canonical makes a redelivery observable on
     * purpose — a rebuilt budget projection reports {@code UNCHANGED} rather than {@code CREATED},
     * and that is the write path recognising itself rather than a discrepancy. What must not differ
     * is the state left behind, which is what the snapshot comparison checks.
     */
    private <T> T step(Delivery delivery, Restart restart, Process process,
            java.util.function.Function<Process, T> action) {
        T first = action.apply(restart == Restart.NEVER ? process : new Process());
        if (delivery == Delivery.ONCE) {
            return first;
        }
        return action.apply(restart == Restart.NEVER ? process : new Process());
    }

    private ResourceReservation reserve(Process process) {
        return process.reservations.reserve(new ReservationOpening(
                ResourceReservation.cash(INTENT, "USD", RESERVED, T0),
                SCOPE, FLOW, EVENTS.get(1), CASH_PINS,
                ReservationPricing.buyingPower(
                        PRICE, T0, MARKET_HASH, decimal("30"), decimal("0.02"),
                        decimal("0.06"), decimal("0.92"))));
    }

    private UUID placeOrder(Process process) {
        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                INTENT, CANDIDATE, INSTRUMENT, OrderSide.BUY, QUANTITY, OrderType.MARKET,
                TimeInForce.DAY, null, null, null, null), T0);
        return process.orders.createAccepted(
                lifecycle.terms(), T0, SCOPE, ORDER_PINS, EVENTS.get(2)).orderId();
    }

    private ResourceReservation attach(Process process, UUID componentId) {
        return process.reservations.attachToOrderComponent(
                new ReservationComponentLink(SCOPE, reservationId(), componentId));
    }

    private UUID fill(Process process, int execution, BigDecimal quantity, UUID botEventId,
            Instant occurredAt) {
        UUID componentId = componentOf(orderId());
        BigDecimal gross = grossOf(quantity);
        BigDecimal fee = feeOf(quantity);
        BigDecimal cash = cashOf(quantity).negate();
        FillRecord record = FillRecord.original(
                orderId(), "f16-execution-" + execution, quantity, PRICE, fee,
                decimal("0.01"), occurredAt, occurredAt);
        // assert_order_fill_state compares the order projection against its effective fills at
        // commit, so recording the fill and moving the order are one transaction or neither.
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    process.fills.record(new FillPosting(
                            record, SCOPE, botEventId, FEE_POLICY, 20, PRECISION, PRICE, occurredAt,
                            MARKET_HASH, gross, gross, cash, "fill-allocation:v1",
                            List.of(new FillAllocation(componentId, 1, quantity, gross, fee, cash))));
                    process.orders.applyFill(new FillOrderCommand(
                            UUID.randomUUID(), orderId(), botEventId, execution, quantity,
                            occurredAt));
                });
        return record.fillRecordId();
    }

    private ResourceReservation consume(
            Process process, UUID fillId, BigDecimal consumed, long expectedSequence) {
        return process.reservations.consume(new ConsumeReservationCommand(
                reservationId(), expectedSequence, EVENTS.get(7), fillId, consumed,
                T0.plusSeconds(3)));
    }

    private ResourceReservation settle(
            Process process, UUID fillId, BigDecimal consumed, long expectedSequence) {
        return process.reservations.settle(new SettleReservationCommand(
                reservationId(), expectedSequence, EVENTS.get(8), fillId, consumed,
                T0.plusSeconds(4)));
    }

    private UUID openLot(Process process, UUID fillId, BigDecimal quantity, Instant openedAt) {
        UUID allocationId = jdbc.sql(
                        "select id from trading.fill_component_allocations where fill_id = :fillId")
                .param("fillId", fillId).query(UUID.class).single();
        process.lots.open(new LotOpening(
                SCOPE, FLOW, INSTRUMENT, componentOf(orderId()), allocationId,
                botEventOfFill(fillId), LotSide.LONG, quantity, grossOf(quantity),
                feeOf(quantity), openedAt));
        return allocationId;
    }

    private LedgerTransaction post(Process process, UUID botEventId, BigDecimal amount) {
        return process.ledger.post(new PostLedgerTransactionCommand(BOT, PARTITION,
                LedgerTransaction.standard(botEventId, T0.plusSeconds(5), List.of(
                        LedgerEntryDraft.debit("SECURITY", "USD", amount),
                        LedgerEntryDraft.credit("CASH", "USD", amount)))));
    }

    private BudgetRebuildResult rebuildBudget(Process process) {
        BotBudgetProjection bot = new BotBudgetProjection(
                BOT, "USD", decimal("969.94"), BigDecimal.ZERO, decimal("30.06"),
                BigDecimal.ZERO, BigDecimal.ZERO, T0.plusSeconds(6), "VALUED", 9);
        PartitionBudgetProjection partition = new PartitionBudgetProjection(
                PARTITION, BOT, "USD", decimal("1000"), BigDecimal.ZERO, decimal("30.06"),
                BigDecimal.ZERO, BigDecimal.ZERO, T0.plusSeconds(6), "VALUED", 9);
        return process.budget.rebuild(new BudgetProjectionRebuild(bot, List.of(partition)));
    }

    private BotStopSettlement stop(Process process) {
        return process.stops.requestStop(new RequestBotStopCommand(
                BOT, StopReason.USER_REQUEST, "F16 bundle stop", T0.plusSeconds(7)));
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * Every canonical row the bundle writes, rendered in a stable order. Comparing the whole shape
     * is what makes a duplicated row or a drifting total fail, rather than only the totals someone
     * thought to assert.
     */
    private String snapshot() {
        StringBuilder text = new StringBuilder();
        for (String table : CANONICAL_TABLES) {
            text.append("## ").append(table).append('\n');
            for (String row : rowsOf(table)) {
                text.append(row).append('\n');
            }
        }
        return text.toString();
    }

    private List<String> rowsOf(String table) {
        return jdbc.sql("select * from trading." + table)
                .query((ResultSet resultSet, int rowNumber) -> renderRow(resultSet))
                .list()
                .stream()
                .sorted()
                .toList();
    }

    private static String renderRow(ResultSet resultSet) throws java.sql.SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        StringBuilder row = new StringBuilder();
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String name = metaData.getColumnLabel(column);
            if (GENERATED_COLUMNS.contains(name)) {
                continue;
            }
            row.append(name).append('=').append(stable(resultSet.getString(column))).append(' ');
        }
        return row.toString();
    }

    /** Masks identifiers so two runs compare on the values and the row shape they left behind. */
    private static String stable(String value) {
        return value == null ? null : UUID_PATTERN.matcher(value).replaceAll("<id>");
    }

    private void clear() {
        try {
            clearTradingRows();
        } catch (Exception failure) {
            throw new IllegalStateException("unable to clear the canonical tables", failure);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Every store built again, which is what the chain sees after a restart. */
    private static final class Process {
        private final ResourceReservationService reservations = new ResourceReservationService(
                new PostgresResourceReservationStore(JdbcClient.create(dataSource), transactions));
        private final OrderLifecycleService orders = new OrderLifecycleService(
                new OrderLifecycleFactory(),
                new PostgresOrderLifecycleStore(JdbcClient.create(dataSource), transactions));
        private final FillRecordService fills = new FillRecordService(
                new PostgresFillRecordStore(JdbcClient.create(dataSource), transactions));
        private final LedgerPostingService ledger = new LedgerPostingService(
                new PostgresLedgerStore(JdbcClient.create(dataSource), transactions));
        private final PositionLotService lots = new PositionLotService(
                new PostgresPositionLotStore(JdbcClient.create(dataSource), transactions));
        private final BudgetProjectionService budget = new BudgetProjectionService(
                new PostgresBudgetProjectionStore(JdbcClient.create(dataSource), transactions));
        private final BotStopOrchestrator stops = new BotStopOrchestrator(
                new PostgresBotStopSettlementStore(
                        new PostgresBotEventStore(JdbcClient.create(dataSource), transactions),
                        JdbcClient.create(dataSource), transactions),
                (botId, operationId) -> StopStepResult.completed("evaluation blocked"),
                (botId, operationId) -> StopStepResult.completed("no open orders remain"),
                (botId, operationId) -> StopStepResult.completed("no position to liquidate"));
    }

    private void seedApprovedIntent() {
        jdbc.sql("""
                insert into trading.order_intent_batches (id, bot_id, partition_id, source_event_id,
                    status, conflict_policy_hash, composition_rules_version, input_state_hash,
                    result_hash, finalized_at)
                values (:id, :bot, :partition, :event, 'FINALIZED', :hash,
                    'order-intent-composition:v1', :hash, :hash, '2026-08-01T00:00:00+00')
                on conflict do nothing
                """).param("id", INTENT_BATCH).param("bot", BOT).param("partition", PARTITION)
                .param("event", EVENTS.getFirst()).param("hash", "e".repeat(64)).update();
        jdbc.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, 'BUY', 'OPEN_LONG', 'MARKET', 'DAY',
                    :quantity, :quantity, :quantity, 'APPROVED', 'ELIGIBLE')
                on conflict do nothing
                """).param("id", INTENT).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + CANDIDATE).param("quantity", QUANTITY).update();
    }

    /** Canonical reaches the intent through the component the order was composed from. */
    private UUID orderId() {
        return jdbc.sql("select order_id from trading.order_components where intent_id = :intentId")
                .param("intentId", INTENT).query(UUID.class).single();
    }

    private UUID reservationId() {
        return jdbc.sql("select id from trading.resource_reservations where intent_id = :intentId")
                .param("intentId", INTENT).query(UUID.class).single();
    }

    private UUID componentOf(UUID orderId) {
        return jdbc.sql("select id from trading.order_components where order_id = :orderId")
                .param("orderId", orderId).query(UUID.class).single();
    }

    private UUID botEventOfFill(UUID fillId) {
        return jdbc.sql("select bot_event_id from trading.fills where id = :fillId")
                .param("fillId", fillId).query(UUID.class).single();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from trading." + table).query(Long.class).single();
    }

    private String one(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static BigDecimal grossOf(BigDecimal quantity) {
        return quantity.multiply(PRICE).setScale(8);
    }

    /** The official 0.2% fee, which is what the fee policy version above pins. */
    private static BigDecimal feeOf(BigDecimal quantity) {
        return grossOf(quantity).multiply(new BigDecimal("0.002")).setScale(8);
    }

    private static BigDecimal cashOf(BigDecimal quantity) {
        return grossOf(quantity).add(feeOf(quantity));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(8);
    }
}
