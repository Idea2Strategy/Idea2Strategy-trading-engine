package com.idea2strategy.trading.persistence.fill;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.fill.FillRecordConflictException;
import com.idea2strategy.trading.application.order.FillOrderCommand;
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
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.order.JooqOrderLifecycleQuery;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the fill write path against the real canonical schema.
 *
 * <p>Fills and orders are exercised as one unit on purpose. Canonical checks two things at commit
 * that no single store can satisfy alone: the allocations of a fill must sum exactly to its
 * economics, and the order projection must equal the sum of its fills. Every test therefore posts
 * the fill and advances the order in the same transaction, which is how the write path has to be
 * used.
 */
@Testcontainers(disabledWithoutDocker = true)
class FillRecordPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("14000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("16000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("17000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_BATCH = UUID.fromString("18000000-0000-4000-8000-000000000001");
    private static final UUID INTENT = UUID.fromString("19000000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T00:02:00Z");

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", "precision-rules:v1", "order-intent-composition:v1");

    private static final int EVENT_POOL = 10;
    private static final List<UUID> EVENTS = new ArrayList<>();

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbcClient;
    private static JdbcTransactionManager transactionManager;
    private static PostgresFillRecordStore fillStore;
    private static PostgresOrderLifecycleStore orderStore;
    private static JooqOrderLifecycleQuery orderQuery;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new JdbcTransactionManager(dataSource);
        fillStore = new PostgresFillRecordStore(jdbcClient, transactionManager);
        orderStore = new PostgresOrderLifecycleStore(jdbcClient, transactionManager);
        orderQuery = new JooqOrderLifecycleQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("13000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services; seeded with referential triggers off
        // exactly as the canonical contract fixtures do. The writes under test run with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('a0000000-0000-4000-8000-000000000001', 'ACTIVE',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000001', 'BASIC', 'Fill bot',
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
                            'fill-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
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

        jdbcClient.sql("""
                insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                    calculation_rules_version, rules_hash, effective_from, published_at)
                values (:id, 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', :hash,
                    '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                """).param("id", FEE_POLICY).param("hash", "f".repeat(64)).update();
        jdbcClient.sql("""
                insert into trading.order_intent_batches (id, bot_id, partition_id, source_event_id,
                    status, conflict_policy_hash, composition_rules_version, input_state_hash,
                    result_hash, finalized_at)
                values (:id, :bot, :partition, :event, 'FINALIZED', :hash,
                    'order-intent-composition:v1', :hash, :hash, '2026-08-01T00:00:00+00')
                """).param("id", INTENT_BATCH).param("bot", BOT).param("partition", PARTITION)
                .param("event", EVENTS.getFirst()).param("hash", "e".repeat(64)).update();
        jdbcClient.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, 'BUY', 'OPEN_LONG', 'MARKET', 'DAY',
                    5, 5, 5, 'APPROVED', 'ELIGIBLE')
                """).param("id", INTENT).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + CANDIDATE).update();
    }

    /**
     * Removed in one transaction with the checks deferred. The canonical consistency triggers fire
     * per statement otherwise, and a half-cleared order legitimately fails them on the way out.
     */
    @BeforeEach
    void clearTradingRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.fill_adjustments");
            statement.addBatch("delete from trading.fill_component_allocations");
            statement.addBatch("delete from trading.fills");
            statement.addBatch("delete from trading.order_state_projections");
            statement.addBatch("delete from trading.order_events");
            statement.addBatch("delete from trading.order_components");
            statement.addBatch("delete from trading.orders");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void aPartialFillLandsInTheCanonicalShapeAndMovesTheOrder() {
        UUID orderId = createOrder();

        postFill(orderId, "execution-1", "2", EVENTS.get(1), 1);

        List<String> fill = jdbcClient.sql("""
                        select quantity, fill_price, gross_amount, slippage_rate_bps, fee_rate_bps,
                               settlement_cash_delta, provider_fill_key
                        from trading.fills where order_id = :orderId
                        """).param("orderId", orderId)
                .query((rs, row) -> List.of(
                        rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),
                        rs.getBigDecimal("fill_price").stripTrailingZeros().toPlainString(),
                        rs.getBigDecimal("gross_amount").stripTrailingZeros().toPlainString(),
                        Integer.toString(rs.getInt("slippage_rate_bps")),
                        Integer.toString(rs.getInt("fee_rate_bps")),
                        rs.getBigDecimal("settlement_cash_delta").stripTrailingZeros().toPlainString(),
                        rs.getString("provider_fill_key")))
                .single();
        var projection = orderQuery.findProjection(orderId).orElseThrow();

        assertAll(
                () -> assertEquals("2", fill.get(0)),
                () -> assertEquals("10", fill.get(1)),
                () -> assertEquals("20", fill.get(2)),
                () -> assertEquals("5", fill.get(3), "canonical pins slippage at 0.05%"),
                () -> assertEquals("20", fill.get(4), "canonical pins the fee at 20 bps"),
                () -> assertEquals("-20.04", fill.get(5), "cash leaves on a buy"),
                () -> assertEquals("execution-1", fill.get(6)),
                () -> assertEquals("OPEN", projection.status()),
                () -> assertEquals(0, new BigDecimal("2").compareTo(projection.filledQuantity())),
                () -> assertEquals(0, new BigDecimal("3").compareTo(projection.remainingQuantity())),
                () -> assertEquals(1, allocationCount(orderId)));
    }

    /** The canonical invariant that forces fills and orders to move together. */
    @Test
    void aFillThatLeavesTheOrderProjectionBehindIsRefusedAtCommit() {
        UUID orderId = createOrder();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // The check is deferred, so it surfaces as a failed commit rather than a failed statement.
        assertThrows(TransactionException.class, () -> transaction.executeWithoutResult(status ->
                fillStore.appendOrLoad(posting(orderId, "execution-orphan", "2", EVENTS.get(2)))));

        assertEquals(0, fillCount(orderId));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                orderQuery.findProjection(orderId).orElseThrow().filledQuantity()));
    }

    @Test
    void allocationsThatDoNotSumToTheFillAreRefused() {
        UUID orderId = createOrder();
        UUID componentId = componentId(orderId);
        FillRecord record = FillRecord.original(
                orderId, "execution-2", new BigDecimal("2"), new BigDecimal("10"),
                new BigDecimal("0.04"), new BigDecimal("0.01"), T1, T1);

        assertThrows(IllegalArgumentException.class, () -> new FillPosting(
                record, SCOPE, EVENTS.get(3), FEE_POLICY, 20, "precision-rules:v1",
                new BigDecimal("9.99"), T1, "m".repeat(64), new BigDecimal("20"),
                new BigDecimal("20"), new BigDecimal("-20.04"), "fill-allocation:v1",
                List.of(new FillAllocation(componentId, 1, BigDecimal.ONE, new BigDecimal("10"),
                        new BigDecimal("0.04"), new BigDecimal("-20.04")))));
    }

    @Test
    void redeliveringOneFillRecordsItOnlyOnce() {
        UUID orderId = createOrder();
        postFill(orderId, "execution-3", "2", EVENTS.get(1), 1);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
                fillStore.appendOrLoad(posting(orderId, "execution-3", "2", EVENTS.get(1))));

        assertEquals(1, fillCount(orderId));
        assertEquals(1, allocationCount(orderId));
    }

    @Test
    void aFillReportedWithDifferentEconomicsIsAConflict() {
        UUID orderId = createOrder();
        UUID componentId = componentId(orderId);
        postFill(orderId, "execution-4", "2", EVENTS.get(1), 1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(FillRecordConflictException.class, () -> transaction.executeWithoutResult(status -> {
            FillRecord divergent = FillRecord.original(
                    orderId, "execution-4", new BigDecimal("2"), new BigDecimal("11"),
                    new BigDecimal("0.044"), new BigDecimal("0.011"), T1, T1);
            fillStore.appendOrLoad(new FillPosting(
                    divergent, SCOPE, EVENTS.get(5), FEE_POLICY, 20, "precision-rules:v1",
                    new BigDecimal("10.989"), T1, "m".repeat(64), new BigDecimal("22"),
                    new BigDecimal("22"), new BigDecimal("-22.044"), "fill-allocation:v1",
                    List.of(new FillAllocation(componentId, 1, new BigDecimal("2"),
                            new BigDecimal("22"), new BigDecimal("0.044"),
                            new BigDecimal("-22.044")))));
        }));

        assertEquals(1, fillCount(orderId));
    }

    /**
     * Canonical forbids a correction that moves quantity; a re-reported size has to bust the fill
     * and report a new one. The domain refuses it before the database has to.
     */
    @Test
    void aCorrectionCannotChangeTheFilledQuantity() {
        UUID orderId = createOrder();
        FillRecord original = FillRecord.original(
                orderId, "execution-5", new BigDecimal("2"), new BigDecimal("10"),
                new BigDecimal("0.04"), new BigDecimal("0.01"), T1, T1);
        FillRecord corrected = original.corrected(
                new BigDecimal("3"), new BigDecimal("10"), new BigDecimal("0.06"),
                new BigDecimal("0.015"), T2, T2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new FillPosting(corrected, SCOPE, EVENTS.get(6), FEE_POLICY, 20, "precision-rules:v1",
                        new BigDecimal("9.99"), T1, "m".repeat(64), new BigDecimal("30"),
                        new BigDecimal("30"), new BigDecimal("-30.06"), "fill-allocation:v1",
                        List.of()));

        assertTrue(failure.getMessage().contains("bust the fill"));
    }

    @Test
    void aBustNegatesTheFillAndReturnsTheOrderToUnfilled() {
        UUID orderId = createOrder();
        FillRecord original = postFill(orderId, "execution-6", "2", EVENTS.get(1), 1);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            fillStore.appendOrLoad(new FillPosting(
                    original.busted(T2, T2), SCOPE, EVENTS.get(7), FEE_POLICY, 20,
                    "precision-rules:v1", new BigDecimal("9.99"), T1, "m".repeat(64),
                    new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("-20.04"),
                    "fill-allocation:v1", List.of()));
            jdbcClient.sql("""
                            update trading.order_state_projections
                            set filled_quantity = 0, remaining_quantity = 5
                            where order_id = :orderId
                            """).param("orderId", orderId).update();
        });

        String adjustment = jdbcClient.sql("""
                        select adjustment_type, quantity_delta
                        from trading.fill_adjustments where fill_id = :fillId
                        """).param("fillId", original.fillRecordId())
                .query((rs, row) -> rs.getString("adjustment_type") + ":"
                        + rs.getBigDecimal("quantity_delta").stripTrailingZeros().toPlainString())
                .single();

        assertEquals("REVERSAL:-2", adjustment);
        assertEquals(0, BigDecimal.ZERO.compareTo(
                orderQuery.findProjection(orderId).orElseThrow().filledQuantity()));
    }

    private static UUID createOrder() {
        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                INTENT, CANDIDATE, INSTRUMENT, OrderSide.BUY, new BigDecimal("5"),
                OrderType.MARKET, TimeInForce.DAY, null, null, null, null), CREATED_AT);
        orderStore.createOrLoad(new OrderPlacement(
                lifecycle, SCOPE, PINS, EVENTS.getFirst(),
                List.of(new OrderComponent(INTENT, new BigDecimal("5"), 1))));
        return lifecycle.orderId();
    }

    /** Posts a fill and advances the order in one transaction, as canonical requires. */
    private static FillRecord postFill(
            UUID orderId, String executionId, String quantity, UUID botEventId, long expectedVersion) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            FillRecord stored =
                    fillStore.appendOrLoad(posting(orderId, executionId, quantity, botEventId));
            orderStore.apply(new FillOrderCommand(
                    UUID.randomUUID(), orderId, botEventId, expectedVersion,
                    new BigDecimal(quantity), T1));
            return stored;
        });
    }

    private static FillPosting posting(
            UUID orderId, String executionId, String quantity, UUID botEventId) {
        BigDecimal size = new BigDecimal(quantity);
        BigDecimal price = new BigDecimal("10");
        BigDecimal gross = size.multiply(price);
        BigDecimal fee = gross.multiply(new BigDecimal("0.002"));
        BigDecimal cash = gross.add(fee).negate();
        FillRecord record = FillRecord.original(
                orderId, executionId, size, price, fee, new BigDecimal("0.01"), T1, T1);
        return new FillPosting(
                record, SCOPE, botEventId, FEE_POLICY, 20, "precision-rules:v1",
                new BigDecimal("9.99"), T1, "m".repeat(64), gross, gross, cash,
                "fill-allocation:v1",
                List.of(new FillAllocation(componentId(orderId), 1, size, gross, fee, cash)));
    }

    private static UUID componentId(UUID orderId) {
        return jdbcClient.sql("select id from trading.order_components where order_id = :orderId")
                .param("orderId", orderId).query(UUID.class).single();
    }

    private static int fillCount(UUID orderId) {
        return jdbcClient.sql("select count(*) from trading.fills where order_id = :orderId")
                .param("orderId", orderId).query(Integer.class).single();
    }

    private static int allocationCount(UUID orderId) {
        return jdbcClient.sql(
                        "select count(*) from trading.fill_component_allocations where order_id = :orderId")
                .param("orderId", orderId).query(Integer.class).single();
    }
}
