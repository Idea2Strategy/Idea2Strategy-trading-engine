package com.idea2strategy.trading.persistence.position;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.position.PositionConflictException;
import com.idea2strategy.trading.application.position.PositionMutationResult;
import com.idea2strategy.trading.domain.fill.FillAllocation;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPlacement;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.position.LotClosing;
import com.idea2strategy.trading.domain.position.LotOpening;
import com.idea2strategy.trading.domain.position.LotSide;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
 * Proves the FIFO position write path against the real canonical schema.
 *
 * <p>A canonical lot cannot be invented. It is opened by one exact
 * {@code fill_component_allocations} row, which needs a fill, an order, a component and an intent
 * whose {@code position_effect} matches the side of the lot, and the deferred provenance triggers
 * check every one of those at commit. Each test therefore builds the real chain through the order
 * and fill write paths rather than seeding lots directly.
 */
@Testcontainers(disabledWithoutDocker = true)
class PositionLotPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("21000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("22000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("24000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("26000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("27000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_BATCH = UUID.fromString("28000000-0000-4000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", "precision-rules:v1", "order-intent-composition:v1");

    private static final int EVENT_POOL = 24;
    private static final List<UUID> EVENTS = new ArrayList<>();

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static PostgresPositionLotStore store;
    private static JooqPositionLotQuery query;
    private static PostgresOrderLifecycleStore orders;
    private static PostgresFillRecordStore fills;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqPositionLotQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
        orders = new PostgresOrderLifecycleStore(jdbc, transactions);
        fills = new PostgresFillRecordStore(jdbc, transactions);

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("23000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services; seeded with referential triggers off
        // exactly as the canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('a0000000-0000-4000-8000-000000000002', 'ACTIVE',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000002', 'BASIC', 'Position bot',
                        'RUNNING', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
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
                            'position-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                            '{}')
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
                insert into trading.order_intent_batches (id, bot_id, partition_id, source_event_id,
                    status, conflict_policy_hash, composition_rules_version, input_state_hash,
                    result_hash, finalized_at)
                values (:id, :bot, :partition, :event, 'FINALIZED', :hash,
                    'order-intent-composition:v1', :hash, :hash, '2026-08-01T00:00:00+00')
                """).param("id", INTENT_BATCH).param("bot", BOT).param("partition", PARTITION)
                .param("event", EVENTS.getFirst()).param("hash", "e".repeat(64)).update();
    }

    /**
     * Removed in one transaction with the checks deferred. The canonical consistency triggers fire
     * per statement otherwise, and a half-cleared position legitimately fails them on the way out.
     */
    @BeforeEach
    void clearTradingRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.partition_position_projections");
            statement.addBatch("delete from trading.flow_position_projections");
            statement.addBatch("delete from trading.position_lot_projections");
            statement.addBatch("delete from trading.lot_movements");
            statement.addBatch("delete from trading.position_lots");
            statement.addBatch("delete from trading.fill_component_allocations");
            statement.addBatch("delete from trading.fills");
            statement.addBatch("delete from trading.order_state_projections");
            statement.addBatch("delete from trading.order_events");
            statement.addBatch("delete from trading.order_components");
            statement.addBatch("delete from trading.orders");
            statement.addBatch("delete from trading.order_intents");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void anOpenedLotLandsInTheCanonicalShapeWithItsMovementAndThreeProjections() {
        Allocation buy = buy(1, "2", "10", 1);

        PositionMutationResult result = store.open(opening(buy));

        var lot = query.lots(BOT, PARTITION, FLOW, INSTRUMENT).getFirst();
        var movements = query.movements(buy.allocationId());
        var flow = query.flow(FLOW, INSTRUMENT).orElseThrow();
        var partition = query.partition(PARTITION, INSTRUMENT).orElseThrow();

        assertAll(
                () -> assertEquals("LONG", lot.lotSide()),
                () -> assertEquals(buy.allocationId(), lot.openingFillAllocationId()),
                () -> assertEquals(buy.componentId(), lot.openingOrderComponentId()),
                () -> assertDecimal("2", lot.openedQuantity()),
                () -> assertDecimal("20.04", lot.openedCostBasisAmount(), "the fee is capitalised"),
                () -> assertDecimal("10.02", lot.unitCost(), "unit_cost is the all-in cost"),
                () -> assertDecimal("0", lot.activeReservedQuantity()),
                () -> assertEquals(1, movements.size()),
                () -> assertEquals("OPEN", movements.getFirst().movementType()),
                () -> assertEquals(buy.botEventId(), movements.getFirst().botEventId()),
                () -> assertEquals(lot.lastMovementId(), movements.getFirst().movementId(),
                        "the projection points at the movement that produced it"),
                () -> assertDecimal("2", flow.longQuantity()),
                () -> assertDecimal("0", flow.shortQuantity()),
                () -> assertDecimal("20.04", flow.costBasisAmount()),
                () -> assertEquals(64, flow.projectionHash().length()),
                () -> assertDecimal("2", partition.netQuantity()),
                () -> assertDecimal("10.02", partition.averageCost().orElseThrow()),
                () -> assertDecimal("0", partition.realizedPnl()),
                () -> assertEquals("UNVALUED", partition.valuationStatus()),
                () -> assertDecimal("2", result.quantityDelta()),
                () -> assertDecimal("20.04", result.costBasisDelta()),
                () -> assertDecimal("2", result.endingLongQuantity()));
    }

    @Test
    void aFifoCloseSpansLotsAndRealisesTheNetResultOfTheClosingAllocation() {
        Allocation first = buy(1, "1", "100", 1);
        Allocation second = buy(2, "2", "110", 2);
        store.open(opening(first));
        store.open(opening(second));
        Allocation sale = sell(3, "1.5", "120", 3);

        PositionMutationResult result = store.closeLong(closing(sale));

        var lots = query.lots(BOT, PARTITION, FLOW, INSTRUMENT);
        var movements = query.movements(sale.allocationId());
        var flow = query.flow(FLOW, INSTRUMENT).orElseThrow();
        var partition = query.partition(PARTITION, INSTRUMENT).orElseThrow();

        // 1 @ 100 costs 100.20 and 2 @ 110 costs 220.44. Selling 1.5 for 180 less 0.36 of fee takes
        // all of the first lot and a quarter of the second, so it releases 100.20 + 55.11 and
        // realises 179.64 - 155.31.
        assertAll(
                () -> assertEquals(2, result.affectedLots()),
                () -> assertDecimal("-1.5", result.quantityDelta()),
                () -> assertDecimal("-155.31", result.costBasisDelta()),
                () -> assertDecimal("24.33", result.realizedPnl()),
                () -> assertDecimal("1.5", result.endingLongQuantity()),
                () -> assertDecimal("165.33", result.endingCostBasisAmount()),
                () -> assertEquals(2, movements.size()),
                () -> assertTrue(movements.stream().allMatch(m -> "CLOSE".equals(m.movementType()))),
                () -> assertDecimal("0", lots.getFirst().remainingQuantity()),
                () -> assertTrue(lots.getFirst().closedAt().isPresent()),
                () -> assertDecimal("1.5", lots.get(1).remainingQuantity()),
                () -> assertDecimal("165.33", lots.get(1).remainingCostBasisAmount()),
                () -> assertDecimal("1.5", flow.longQuantity()),
                () -> assertDecimal("165.33", flow.costBasisAmount()),
                () -> assertDecimal("1.5", partition.netQuantity()),
                () -> assertDecimal("110.22", partition.averageCost().orElseThrow()),
                () -> assertDecimal("24.33", partition.realizedPnl()));
    }

    @Test
    void aFullCloseLeavesNoBasisResidueBehind() {
        Allocation buy = buy(1, "3", "10", 1);
        store.open(opening(buy));
        Allocation sale = sell(2, "3", "12", 2);

        PositionMutationResult result = store.closeLong(closing(sale));

        var lot = query.lots(BOT, PARTITION, FLOW, INSTRUMENT).getFirst();
        var flow = query.flow(FLOW, INSTRUMENT).orElseThrow();
        var partition = query.partition(PARTITION, INSTRUMENT).orElseThrow();

        assertAll(
                () -> assertDecimal("0", result.endingLongQuantity()),
                () -> assertDecimal("0", result.endingCostBasisAmount()),
                () -> assertDecimal("0", lot.remainingQuantity()),
                () -> assertDecimal("0", lot.remainingCostBasisAmount()),
                () -> assertTrue(lot.closedAt().isPresent()),
                () -> assertDecimal("0", flow.longQuantity()),
                () -> assertDecimal("0", partition.netQuantity()),
                () -> assertTrue(partition.averageCost().isEmpty(),
                        "a flat partition reports no average cost"),
                // 36 less 0.072 of fee against 30.06 of basis.
                () -> assertDecimal("5.868", partition.realizedPnl()));
    }

    /** Canonical makes the allocation unique, so a redelivery has to land on the rows it wrote. */
    @Test
    void redeliveringAnOpenAndACloseWritesNeitherASecondTime() {
        Allocation buy = buy(1, "2", "10", 1);
        PositionMutationResult opened = store.open(opening(buy));
        assertEquals(opened, newStore().open(opening(buy)));

        Allocation sale = sell(2, "1", "11", 2);
        PositionMutationResult closed = store.closeLong(closing(sale));
        assertEquals(closed, newStore().closeLong(closing(sale)));

        assertAll(
                () -> assertEquals(1, query.lots(BOT, PARTITION, FLOW, INSTRUMENT).size()),
                () -> assertEquals(1, query.movements(buy.allocationId()).size()),
                () -> assertEquals(1, query.movements(sale.allocationId()).size()),
                () -> assertDecimal("1", query.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()));
    }

    /**
     * The allocation row is immutable canonical evidence, so economics that disagree with it are a
     * conflict rather than a second write — no private fingerprint receipt required.
     */
    @Test
    void anAllocationReportedWithDifferentEconomicsIsAConflict() {
        Allocation buy = buy(1, "2", "10", 1);
        store.open(opening(buy));

        LotOpening divergent = new LotOpening(
                SCOPE, FLOW, INSTRUMENT, buy.componentId(), buy.allocationId(), EVENTS.get(4),
                LotSide.LONG, new BigDecimal("2"), new BigDecimal("22"), new BigDecimal("0.044"), T0);

        PositionConflictException failure =
                assertThrows(PositionConflictException.class, () -> store.open(divergent));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("gross amount")),
                () -> assertEquals(1, query.lots(BOT, PARTITION, FLOW, INSTRUMENT).size()),
                () -> assertDecimal("20.04",
                        query.flow(FLOW, INSTRUMENT).orElseThrow().costBasisAmount()));
    }

    /** One of two racing closes wins; the other leaves nothing behind. */
    @Test
    void twoConcurrentClosesOfTheSamePositionCannotBothSucceed() throws Exception {
        Allocation buy = buy(1, "1", "10", 1);
        store.open(opening(buy));
        Allocation firstSale = sell(2, "1", "11", 2);
        Allocation secondSale = sell(3, "1", "12", 3);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<Boolean> left = pool.submit(closeRace(ready, firstSale));
            Future<Boolean> right = pool.submit(closeRace(ready, secondSale));
            boolean leftWon = left.get(60, TimeUnit.SECONDS);
            boolean rightWon = right.get(60, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(leftWon ^ rightWon, "exactly one close may consume the lot"),
                    () -> assertDecimal("0", query.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()),
                    () -> assertEquals(1, jdbc.sql(
                                    "select count(*) from trading.lot_movements where movement_type = 'CLOSE'")
                            .query(Integer.class).single()));
        } finally {
            pool.shutdownNow();
        }
    }

    /** An over-close reaches no table at all, so the position it could not cover is untouched. */
    @Test
    void anOverCloseRollsBackWithoutMovingThePosition() {
        Allocation buy = buy(1, "1", "10", 1);
        store.open(opening(buy));
        Allocation tooLarge = sell(2, "1.1", "11", 2);

        assertThrows(PositionConflictException.class, () -> store.closeLong(closing(tooLarge)));

        var lot = query.lots(BOT, PARTITION, FLOW, INSTRUMENT).getFirst();
        assertAll(
                () -> assertDecimal("1", query.flow(FLOW, INSTRUMENT).orElseThrow().longQuantity()),
                () -> assertDecimal("1", lot.remainingQuantity()),
                () -> assertDecimal("10.02", lot.remainingCostBasisAmount()),
                () -> assertTrue(lot.closedAt().isEmpty()),
                () -> assertEquals(0, query.movements(tooLarge.allocationId()).size()),
                () -> assertDecimal("0",
                        query.partition(PARTITION, INSTRUMENT).orElseThrow().realizedPnl()));
    }

    /**
     * A lot may only be opened by an allocation whose intent approved that direction. Canonical
     * asserts it at commit; the store refuses it at the boundary that caused it.
     */
    @Test
    void aLotCannotBeOpenedFromAClosingAllocation() {
        Allocation buy = buy(1, "2", "10", 1);
        store.open(opening(buy));
        Allocation sale = sell(2, "1", "11", 2);

        LotOpening wrongWayRound = new LotOpening(
                SCOPE, FLOW, INSTRUMENT, sale.componentId(), sale.allocationId(), EVENTS.get(6),
                LotSide.LONG, sale.quantity(), sale.gross(), sale.fee(), T0.plusSeconds(2));

        PositionConflictException failure =
                assertThrows(PositionConflictException.class, () -> store.open(wrongWayRound));

        assertTrue(failure.getMessage().contains("position effect"));
        assertEquals(1, query.lots(BOT, PARTITION, FLOW, INSTRUMENT).size());
    }

    private static Callable<Boolean> closeRace(CountDownLatch ready, Allocation sale) {
        return () -> {
            ready.countDown();
            ready.await(30, TimeUnit.SECONDS);
            try {
                newStore().closeLong(closing(sale));
                return true;
            } catch (RuntimeException refused) {
                return false;
            }
        };
    }

    private static LotOpening opening(Allocation allocation) {
        return new LotOpening(
                SCOPE, FLOW, INSTRUMENT, allocation.componentId(), allocation.allocationId(),
                allocation.botEventId(), LotSide.LONG, allocation.quantity(), allocation.gross(),
                allocation.fee(), allocation.occurredAt());
    }

    private static LotClosing closing(Allocation allocation) {
        return new LotClosing(
                SCOPE, FLOW, INSTRUMENT, allocation.componentId(), allocation.allocationId(),
                allocation.botEventId(), LotSide.LONG, allocation.quantity(), allocation.gross(),
                allocation.fee(), allocation.occurredAt());
    }

    private static Allocation buy(int index, String quantity, String price, int secondsIn) {
        return trade(index, quantity, price, secondsIn, OrderSide.BUY, "OPEN_LONG");
    }

    private static Allocation sell(int index, String quantity, String price, int secondsIn) {
        return trade(index, quantity, price, secondsIn, OrderSide.SELL, "CLOSE_LONG");
    }

    /**
     * Builds the whole canonical chain one lot movement needs: an intent that approved the
     * direction, an accepted order composed of it, a fill and the allocation of that fill to the
     * component. The order and fill write paths do the work, because canonical checks their
     * invariants at commit too.
     */
    private static Allocation trade(
            int index, String quantity, String price, int secondsIn, OrderSide side, String effect) {
        UUID intentId = UUID.fromString("29000000-0000-4000-8000-%012d".formatted(index));
        UUID candidateId = UUID.fromString("2a000000-0000-4000-8000-%012d".formatted(index));
        // Canonical makes order_events.bot_event_id unique, so acceptance and the fill of each
        // trade need an official event of their own.
        UUID acceptedEventId = EVENTS.get(index * 2 - 2);
        UUID botEventId = EVENTS.get(index * 2 - 1);
        BigDecimal size = new BigDecimal(quantity);
        BigDecimal unitPrice = new BigDecimal(price);
        BigDecimal gross = size.multiply(unitPrice).setScale(8);
        BigDecimal fee = gross.multiply(new BigDecimal("0.002")).setScale(8);
        BigDecimal cash = side == OrderSide.BUY ? gross.add(fee).negate() : gross.subtract(fee);
        Instant occurredAt = T0.plusSeconds(secondsIn);

        jdbc.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, cast(:side as trading.order_side),
                    cast(:effect as trading.position_effect), 'MARKET', 'DAY',
                    :quantity, :quantity, :quantity, 'APPROVED', 'ELIGIBLE')
                """)
                .param("id", intentId).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + candidateId).param("side", side.name())
                .param("effect", effect).param("quantity", size).update();

        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                intentId, candidateId, INSTRUMENT, side, size, OrderType.MARKET, TimeInForce.DAY,
                null, null, null, null), T0);
        orders.createOrLoad(new OrderPlacement(
                lifecycle, SCOPE, PINS, acceptedEventId,
                List.of(new OrderComponent(intentId, size, 1))));
        UUID orderId = lifecycle.orderId();
        UUID componentId = jdbc.sql(
                        "select id from trading.order_components where order_id = :orderId")
                .param("orderId", orderId).query(UUID.class).single();

        FillRecord record = FillRecord.original(
                orderId, "execution-" + index, size, unitPrice, fee, new BigDecimal("0.01"),
                occurredAt, occurredAt);
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    fills.appendOrLoad(new FillPosting(
                            record, SCOPE, botEventId, FEE_POLICY, 20, "precision-rules:v1",
                            unitPrice, occurredAt, "m".repeat(64), gross, gross, cash,
                            "fill-allocation:v1",
                            List.of(new FillAllocation(componentId, 1, size, gross, fee, cash))));
                    orders.apply(new FillOrderCommand(
                            UUID.randomUUID(), orderId, botEventId, 1, size, occurredAt));
                });

        UUID allocationId = jdbc.sql(
                        "select id from trading.fill_component_allocations where fill_id = :fillId")
                .param("fillId", record.fillRecordId()).query(UUID.class).single();
        assertNotNull(allocationId);
        return new Allocation(componentId, allocationId, botEventId, size, gross, fee, occurredAt);
    }

    private static PostgresPositionLotStore newStore() {
        return new PostgresPositionLotStore(JdbcClient.create(dataSource), transactions);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertDecimal(expected, actual, null);
    }

    private static void assertDecimal(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> (message == null ? "" : message + ": ") + "expected " + expected
                        + " but was " + actual);
    }

    private record Allocation(
            UUID componentId,
            UUID allocationId,
            UUID botEventId,
            BigDecimal quantity,
            BigDecimal gross,
            BigDecimal fee,
            Instant occurredAt) {
    }
}
