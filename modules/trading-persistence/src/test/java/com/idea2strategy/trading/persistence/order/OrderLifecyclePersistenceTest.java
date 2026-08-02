package com.idea2strategy.trading.persistence.order;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.order.CancelOrderCommand;
import com.idea2strategy.trading.application.order.ExpireOrderCommand;
import com.idea2strategy.trading.application.order.FillOrderCommand;
import com.idea2strategy.trading.application.order.OrderLifecycleConflictException;
import com.idea2strategy.trading.application.order.OrderLifecycleVersionConflictException;
import com.idea2strategy.trading.domain.order.OrderComponent;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderPlacement;
import com.idea2strategy.trading.domain.order.OrderPolicyPins;
import com.idea2strategy.trading.domain.order.OrderScope;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderStatus;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the order write path against the real canonical schema.
 *
 * <p>Four canonical rows carry what the private schema kept in two. The optimistic lock moved from
 * an {@code orders.version} column, which canonical does not have, onto
 * {@code order_state_projections.last_order_event_sequence}; idempotency moved from a command
 * receipt table onto the unique {@code order_events.bot_event_id}. Both are exercised here rather
 * than assumed.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderLifecyclePersistenceTest {

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
    private static final UUID OTHER_INTENT = UUID.fromString("19000000-0000-4000-8000-000000000002");
    private static final UUID CANDIDATE = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_CANDIDATE = UUID.fromString("40000000-0000-4000-8000-000000000002");

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T00:02:00Z");
    private static final Instant T3 = Instant.parse("2026-08-01T00:03:00Z");

    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", "precision-rules:v1", "order-intent-composition:v1");

    /** One seeded official event per transition; canonical makes each one usable exactly once. */
    private static final int EVENT_POOL = 12;
    private static final List<UUID> EVENTS = new ArrayList<>();

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbcClient;
    private static JdbcTransactionManager transactionManager;
    private static PostgresOrderLifecycleStore store;
    private static JooqOrderLifecycleQuery query;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqOrderLifecycleQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        for (int index = 1; index <= EVENT_POOL; index++) {
            EVENTS.add(UUID.fromString("13000000-0000-4000-8000-%012d".formatted(index)));
        }

        // bot.* and market_data.* belong to other services and are seeded with referential triggers
        // off, exactly as the canonical contract fixtures do. The order writes under test then run
        // with the triggers back on, so their canonical foreign keys are genuinely checked.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000001', 'BASIC', 'Order bot',
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
                            'order-seed-%d', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
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

        // Trading-owned parents. The fee policy is platform product data the order pins; the intents
        // are what the order is composed from.
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
        insertIntent(INTENT, CANDIDATE);
        insertIntent(OTHER_INTENT, OTHER_CANDIDATE);
    }

    private static void insertIntent(UUID intentId, UUID candidateId) {
        jdbcClient.sql("""
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                    intent_key, side, position_effect, order_type, time_in_force,
                    requested_quantity, post_netting_quantity, final_quantity, decision,
                    decision_reason_code)
                values (:id, :bot, :batch, :event, 'FLOW_EVALUATION', :evaluation, :partition,
                    :flow, :instrument, :key, 'BUY', 'OPEN_LONG', 'MARKET', 'DAY',
                    5, 5, 5, 'APPROVED', 'ELIGIBLE')
                """).param("id", intentId).param("bot", BOT).param("batch", INTENT_BATCH)
                .param("event", EVENTS.getFirst()).param("evaluation", EVALUATION)
                .param("partition", PARTITION).param("flow", FLOW).param("instrument", INSTRUMENT)
                .param("key", "candidate:" + candidateId).update();
    }

    /**
     * Removed in one transaction with the checks deferred. The canonical consistency triggers fire
     * per statement otherwise, and a half-cleared order legitimately fails them on the way out.
     */
    @BeforeEach
    void clearOrderRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.order_state_projections");
            statement.addBatch("delete from trading.order_events");
            statement.addBatch("delete from trading.order_components");
            statement.addBatch("delete from trading.orders");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void anAcceptedOrderLandsInTheCanonicalShape() {
        OrderPlacement placement = placement(accepted());

        assertEquals(placement.lifecycle(), store.createOrLoad(placement));

        var order = query.findOrder(placement.lifecycle().orderId()).orElseThrow();
        var projection = query.findProjection(placement.lifecycle().orderId()).orElseThrow();
        var component = query.findComponents(placement.lifecycle().orderId()).getFirst();
        var event = query.findEvents(placement.lifecycle().orderId()).getFirst();
        assertAll(
                () -> assertEquals(BOT, order.botId()),
                () -> assertEquals(PARTITION, order.partitionId()),
                () -> assertEquals("order:" + placement.lifecycle().orderId(), order.orderKey()),
                () -> assertEquals("BUY", order.side()),
                () -> assertEquals("MARKET", order.orderType()),
                () -> assertEquals("DAY", order.timeInForce()),
                () -> assertEquals(5, order.slippageRateBps(), "canonical pins 0.05%"),
                () -> assertEquals(FEE_POLICY, order.feePolicyId()),
                () -> assertEquals(EVENTS.getFirst(), order.acceptedEventId()),
                () -> assertEquals(CREATED_AT, order.acceptedAt()),
                () -> assertEquals(placement.contractHash(), order.contractHash()),
                () -> assertEquals(INTENT, component.intentId()),
                () -> assertEquals(1, component.componentSequence()),
                () -> assertEquals(0, new BigDecimal("5").compareTo(component.componentQuantity())),
                () -> assertEquals("OPEN", projection.status()),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.filledQuantity())),
                () -> assertEquals(0, new BigDecimal("5").compareTo(projection.remainingQuantity())),
                () -> assertEquals(1L, projection.lastOrderEventSequence()),
                () -> assertEquals("ORDER_ACCEPTED", event.eventType()),
                () -> assertNull(event.previousStatus()),
                () -> assertEquals("OPEN", event.newStatus()),
                () -> assertEquals(1L, event.orderSequence()));
    }

    @Test
    void aRejectedOrderLeavesNothingOutstanding() {
        OrderPlacement placement = placement(
                new OrderLifecycleFactory().rejected(terms(), CREATED_AT, "RISK_LIMIT_EXCEEDED"));

        store.createOrLoad(placement);

        var projection = query.findProjection(placement.lifecycle().orderId()).orElseThrow();
        var event = query.findEvents(placement.lifecycle().orderId()).getFirst();
        assertAll(
                () -> assertEquals("REJECTED", projection.status()),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.filledQuantity())),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.remainingQuantity())),
                () -> assertEquals("ORDER_REJECTED", event.eventType()),
                () -> assertEquals("RISK_LIMIT_EXCEEDED", event.reasonCode()));
    }

    @Test
    void creationRetryAcrossRestartKeepsOneOrder() {
        OrderPlacement placement = placement(accepted());

        assertEquals(placement.lifecycle(), store.createOrLoad(placement));
        assertEquals(placement.lifecycle(), newStore().createOrLoad(placement));

        assertEquals(1, query.countOrders());
        assertEquals(1, query.countEvents());
        assertEquals(1, query.findComponents(placement.lifecycle().orderId()).size());
    }

    @Test
    void concurrentIdenticalCreationConvergesOnOneOrder() throws Exception {
        OrderPlacement placement = placement(accepted());
        List<Callable<OrderLifecycle>> work = List.of(
                () -> newStore().createOrLoad(placement),
                () -> newStore().createOrLoad(placement));

        var pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<OrderLifecycle> future : pool.invokeAll(work)) {
                assertEquals(placement.lifecycle(), future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, query.countOrders());
        assertEquals(1, query.countEvents());
    }

    /** The same order id under a different contract is a contradiction, not a replay. */
    @Test
    void changedCreationContentConflicts() {
        store.createOrLoad(placement(accepted()));

        OrderPlacement divergent = new OrderPlacement(
                accepted(),
                SCOPE,
                new OrderPolicyPins(FEE_POLICY, "broker-rules:v2", "precision-rules:v1",
                        "order-intent-composition:v1"),
                EVENTS.get(1),
                List.of(new OrderComponent(INTENT, new BigDecimal("5"), 1)));

        assertThrows(OrderLifecycleConflictException.class, () -> newStore().createOrLoad(divergent));
        assertEquals(1, query.countOrders());
    }

    /**
     * Fill transitions live in {@code FillRecordPersistenceTest}. Canonical
     * {@code assert_order_fill_state} compares the projection against the sum of the order's fills
     * at commit, so a fill cannot be exercised here without also writing the canonical fill.
     */
    @Test
    void cancellingAnUnfilledOrderClosesItWithNothingOutstanding() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();

        OrderLifecycle cancelled = store.apply(new CancelOrderCommand(
                UUID.randomUUID(), orderId, EVENTS.get(1), 1, "BOT_STOPPED", T1));

        var projection = query.findProjection(orderId).orElseThrow();
        assertAll(
                () -> assertEquals(OrderStatus.CANCELLED, cancelled.status()),
                () -> assertEquals("CANCELLED", projection.status()),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.filledQuantity())),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.remainingQuantity())),
                () -> assertNull(projection.activeStopPrice()),
                () -> assertEquals(2L, projection.lastOrderEventSequence()),
                () -> assertEquals("ORDER_CANCELLED", query.findEvents(orderId).getLast().eventType()));
    }

    @Test
    void aDayOrderExpiresAtTheSessionClose() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();

        store.apply(new ExpireOrderCommand(UUID.randomUUID(), orderId, EVENTS.get(1), 1, T2, T1));

        var projection = query.findProjection(orderId).orElseThrow();
        var event = query.findEvents(orderId).getLast();
        assertAll(
                () -> assertEquals("EXPIRED", projection.status()),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(projection.remainingQuantity())),
                () -> assertEquals("ORDER_EXPIRED", event.eventType()),
                () -> assertEquals("DAY_SESSION_CLOSE", event.reasonCode()));
    }

    /**
     * The canonical unique {@code bot_event_id} is what absorbs redelivery: the same official cause
     * cannot produce a second transition.
     */
    @Test
    void redeliveringOneOfficialEventAppliesTheTransitionOnlyOnce() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();
        CancelOrderCommand command = cancel(orderId, EVENTS.get(1), 1, T1);

        OrderLifecycle first = store.apply(command);
        OrderLifecycle again = newStore().apply(command);

        assertEquals(first, again);
        assertEquals(2, query.findEvents(orderId).size());
        assertEquals("CANCELLED", query.findProjection(orderId).orElseThrow().status());
    }

    @Test
    void aStaleVersionIsRefusedWithoutWriting() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();
        store.apply(cancel(orderId, EVENTS.get(1), 1, T1));

        assertThrows(
                OrderLifecycleVersionConflictException.class,
                () -> store.apply(cancel(orderId, EVENTS.get(3), 1, T2)));

        assertEquals(2, query.findEvents(orderId).size());
    }

    @Test
    void competingSameVersionCommandsProduceOneWinner() throws Exception {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();

        List<Callable<OrderLifecycle>> work = List.of(
                () -> newStore().apply(cancel(orderId, EVENTS.get(4), 1, T1)),
                () -> newStore().apply(cancel(orderId, EVENTS.get(5), 1, T1)));

        int succeeded = 0;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<OrderLifecycle> future : pool.invokeAll(work)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof OrderLifecycleVersionConflictException
                            || expected.getCause() instanceof OrderLifecycleConflictException);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, succeeded, "both commands claimed the same version");
        assertEquals(2, query.findEvents(orderId).size());
        assertEquals(2L, query.findProjection(orderId).orElseThrow().lastOrderEventSequence());
    }

    @Test
    void anOverfillRollsBackEveryWrite() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();

        assertThrows(
                IllegalArgumentException.class,
                () -> store.apply(fill(orderId, EVENTS.get(6), 1, "6", T1)));

        assertEquals(1, query.findEvents(orderId).size());
        assertEquals(1L, query.findProjection(orderId).orElseThrow().lastOrderEventSequence());
    }

    @Test
    void aTerminalOrderRefusesFurtherTransitions() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();
        store.apply(cancel(orderId, EVENTS.get(1), 1, T1));

        assertThrows(
                IllegalStateException.class,
                () -> store.apply(fill(orderId, EVENTS.get(7), 2, "1", T2)));
        assertThrows(
                IllegalStateException.class,
                () -> store.apply(cancel(orderId, EVENTS.get(8), 2, T3)));

        assertEquals(2, query.findEvents(orderId).size());
    }

    /**
     * Canonical {@code order_events.bot_event_id} is unique across the whole stream, so a second
     * order cannot claim an official event that already caused one.
     */
    @Test
    void eachOfficialEventBacksAtMostOneOrderEvent() {
        OrderPlacement first = placement(accepted());
        store.createOrLoad(first);

        OrderPlacement second = new OrderPlacement(
                new OrderLifecycleFactory().accepted(terms(OTHER_INTENT, OTHER_CANDIDATE), CREATED_AT),
                SCOPE,
                PINS,
                EVENTS.getFirst(),
                List.of(new OrderComponent(OTHER_INTENT, new BigDecimal("5"), 1)));

        assertThrows(OrderLifecycleConflictException.class, () -> newStore().createOrLoad(second));
        assertEquals(1, query.countOrders());
    }

    @Test
    void unexpectedInfrastructureFailurePropagatesAsDataAccessException() {
        OrderPlacement placement = placement(accepted());
        TransactionTemplate ddlTransaction = new TransactionTemplate(transactionManager);

        assertThrows(DataAccessException.class, () -> ddlTransaction.executeWithoutResult(status -> {
            jdbcClient.sql("alter table trading.orders rename to orders_unavailable").update();
            store.createOrLoad(placement);
        }));

        assertEquals(0, query.countOrders());
    }

    @Test
    void everyStoredEventSequenceIsDistinctAndContiguous() {
        OrderPlacement placement = placement(accepted());
        store.createOrLoad(placement);
        UUID orderId = placement.lifecycle().orderId();
        store.apply(cancel(orderId, EVENTS.get(1), 1, T1));

        Set<Long> sequences = new HashSet<>();
        query.findEvents(orderId).forEach(event -> sequences.add(event.orderSequence()));
        assertEquals(2, sequences.size());
        assertEquals(List.of(1L, 2L), List.copyOf(sequences).stream().sorted().toList());
    }

    private static FillOrderCommand fill(
            UUID orderId, UUID botEventId, long expectedVersion, String delta, Instant occurredAt) {
        return new FillOrderCommand(
                UUID.randomUUID(), orderId, botEventId, expectedVersion, new BigDecimal(delta), occurredAt);
    }

    private static CancelOrderCommand cancel(
            UUID orderId, UUID botEventId, long expectedVersion, Instant occurredAt) {
        return new CancelOrderCommand(
                UUID.randomUUID(), orderId, botEventId, expectedVersion, "BOT_STOPPED", occurredAt);
    }

    private static OrderLifecycle accepted() {
        return new OrderLifecycleFactory().accepted(terms(), CREATED_AT);
    }

    private static OrderPlacement placement(OrderLifecycle lifecycle) {
        return new OrderPlacement(
                lifecycle,
                SCOPE,
                PINS,
                EVENTS.getFirst(),
                List.of(new OrderComponent(lifecycle.terms().intentId(), lifecycle.terms().quantity(), 1)));
    }

    private static OrderTerms terms() {
        return terms(INTENT, CANDIDATE);
    }

    private static OrderTerms terms(UUID intentId, UUID candidateId) {
        return new OrderTerms(
                intentId, candidateId, INSTRUMENT, OrderSide.BUY, new BigDecimal("5"),
                OrderType.MARKET, TimeInForce.DAY, null, null, null, null);
    }

    private static PostgresOrderLifecycleStore newStore() {
        return new PostgresOrderLifecycleStore(JdbcClient.create(dataSource), transactionManager);
    }
}
