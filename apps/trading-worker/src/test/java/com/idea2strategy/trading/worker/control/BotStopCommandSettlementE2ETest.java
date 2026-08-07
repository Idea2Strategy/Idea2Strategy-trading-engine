package com.idea2strategy.trading.worker.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessingResult;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.application.port.BotStopSettlementStore;
import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
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
import com.idea2strategy.trading.domain.reservation.ReservationComponentLink;
import com.idea2strategy.trading.domain.reservation.ReservationOpening;
import com.idea2strategy.trading.domain.reservation.ReservationPolicyPins;
import com.idea2strategy.trading.domain.reservation.ReservationPricing;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.order.PostgresOrderLifecycleStore;
import com.idea2strategy.trading.persistence.reservation.PostgresResourceReservationStore;
import com.idea2strategy.trading.persistence.stop.CanonicalBotExecutionGate;
import com.idea2strategy.trading.persistence.stop.PostgresOpenOrderCleanup;
import com.idea2strategy.trading.strategy.runtime.control.BotControlResult;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpoint;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpointStore;
import com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotContractCodec;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotExecutionPlanAdapter;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotOutboxEnvelope;
import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F91: B's stop command reaches the real order and settlement state.
 *
 * <p>The command is a real {@code strategy-bot.v1} envelope, decoded by the real codec, verified
 * against the locked snapshot, and handed to {@link StopSettlingBotLifecycle}, which turns it into
 * a durable settlement over the canonical tables. The bot going into it holds an OPEN order with an
 * ACTIVE attached cash reservation — the state a stop exists to unwind — and the assertions are
 * canonical rows, not port calls: the order ends CANCELLED, the reservation ends released, and a
 * redelivered command changes nothing.
 *
 * <p>A second bot's settlement is left mid-flight on purpose, to observe what BLOCK_NEW_WORK means
 * from the outside: its scoped candidate batches are refused at intake while the settlement is in
 * flight, and accepted again is not asserted — a stopped bot's producer has itself been stopped.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "trading.fake-candidate.enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class BotStopCommandSettlementE2ETest {

    private static final UUID BOT = UUID.fromString("f9100000-0000-4000-8000-000000000001");
    private static final UUID BLOCKED_BOT = UUID.fromString("f9100000-0000-4000-8000-000000000002");
    private static final UUID PARTITION = UUID.fromString("f9110000-0000-4000-8000-000000000001");
    private static final UUID BLOCKED_PARTITION = UUID.fromString("f9110000-0000-4000-8000-000000000002");
    private static final UUID FLOW = UUID.fromString("f9120000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("f9140000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("f9160000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY = UUID.fromString("f9170000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY = UUID.fromString("f9170000-0000-4000-8000-000000000002");
    private static final UUID INTENT_BATCH = UUID.fromString("f9180000-0000-4000-8000-000000000001");
    private static final UUID INTENT = UUID.fromString("f9190000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE = UUID.fromString("f91a0000-0000-4000-8000-000000000001");
    private static final UUID ACCEPT_EVENT = UUID.fromString("f9130000-0000-4000-8000-000000000001");
    private static final UUID RESERVE_EVENT = UUID.fromString("f9130000-0000-4000-8000-000000000002");

    /** A third bot that actually holds something, for the liquidation step. */
    private static final UUID LIQ_BOT = UUID.fromString("f9100000-0000-4000-8000-000000000003");
    private static final UUID LIQ_PARTITION = UUID.fromString("f9110000-0000-4000-8000-000000000003");
    private static final UUID LIQ_FLOW = UUID.fromString("f9120000-0000-4000-8000-000000000003");
    private static final UUID LIQ_EVALUATION = UUID.fromString("f9140000-0000-4000-8000-000000000003");
    private static final UUID LIQ_INTENT_BATCH = UUID.fromString("f9180000-0000-4000-8000-000000000003");
    private static final UUID LIQ_INTENT = UUID.fromString("f9190000-0000-4000-8000-000000000003");
    private static final UUID LIQ_CANDIDATE = UUID.fromString("f91a0000-0000-4000-8000-000000000003");
    private static final UUID LIQ_ACCEPT_EVENT = UUID.fromString("f9130000-0000-4000-8000-000000000003");
    private static final UUID LIQ_FILL_EVENT = UUID.fromString("f9130000-0000-4000-8000-000000000004");
    private static final OrderScope LIQ_SCOPE = new OrderScope(LIQ_BOT, LIQ_PARTITION);

    private static final Instant T0 = Instant.parse("2026-08-01T14:30:00Z");
    private static final String PRECISION = "precision-rules:v1";
    private static final OrderScope SCOPE = new OrderScope(BOT, PARTITION);
    private static final OrderPolicyPins ORDER_PINS = new OrderPolicyPins(
            FEE_POLICY, "broker-rules:v1", PRECISION, "order-intent-composition:v1");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        seed(dataSource);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static void seed(DriverManagerDataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('f91b0000-0000-4000-8000-000000000001', 'ACTIVE',
                        '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00')
                    """);
            for (UUID bot : List.of(BOT, BLOCKED_BOT, LIQ_BOT)) {
                statement.addBatch("""
                        insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                            lifecycle_changed_at, created_at, execution_eligible_from)
                        values ('%s', 'f91b0000-0000-4000-8000-000000000001', 'BASIC', 'F91 %s',
                            'RUNNING', '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00',
                            '2026-07-01T00:00:00+00')
                        """.formatted(bot, bot));
            }
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(BLOCKED_PARTITION, BLOCKED_BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(LIQ_PARTITION, LIQ_BOT, "c".repeat(64)));
            for (Object[] flow : List.of(
                    new Object[] {FLOW, PARTITION}, new Object[] {LIQ_FLOW, LIQ_PARTITION})) {
                statement.addBatch("""
                        insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                            compiled_flow_plan_id, position_x, position_y, semantic_document,
                            layout_document, layout_schema_version, semantic_hash, layout_hash,
                            configuration_hash)
                        values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                            '{}', '{}', 'v1', '%s', '%s', '%s')
                        """.formatted(flow[0], flow[1],
                                "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            }
            for (UUID event : List.of(LIQ_ACCEPT_EVENT, LIQ_FILL_EVENT)) {
                statement.addBatch("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values ('%s', '%s', %d, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                            'f91-liq-seed-%s', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
                        """.formatted(event, LIQ_BOT, event.equals(LIQ_ACCEPT_EVENT) ? 1 : 2, event));
            }
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(LIQ_EVALUATION, LIQ_BOT, LIQ_PARTITION, LIQ_FLOW,
                            LIQ_ACCEPT_EVENT));
            for (UUID event : List.of(ACCEPT_EVENT, RESERVE_EVENT)) {
                statement.addBatch("""
                        insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                            event_schema_version, correlation_id, idempotency_key, occurred_at,
                            received_at, summary_document)
                        values ('%s', '%s', %d, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                            'f91-seed-%s', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
                        """.formatted(event, BOT, event.equals(ACCEPT_EVENT) ? 1 : 2, event));
            }
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-01T00:00:00+00')
                    """.formatted(EVALUATION, BOT, PARTITION, FLOW, ACCEPT_EVENT));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");

            statement.execute("""
                    insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                        calculation_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', '%s',
                        '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                    """.formatted(FEE_POLICY, "f".repeat(64)));
            statement.execute("""
                    insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                        buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'BUYING_POWER_BUFFER', 'v1', 75, 'rounding:v1', '%s',
                        '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')
                    """.formatted(BUFFER_POLICY, "b".repeat(64)));
            statement.execute("""
                    insert into trading.order_intent_batches (id, bot_id, partition_id,
                        source_event_id, status, conflict_policy_hash, composition_rules_version,
                        input_state_hash, result_hash, finalized_at)
                    values ('%s', '%s', '%s', '%s', 'FINALIZED', '%s',
                        'order-intent-composition:v1', '%s', '%s', '2026-08-01T00:00:00+00')
                    """.formatted(INTENT_BATCH, BOT, PARTITION, ACCEPT_EVENT,
                            "e".repeat(64), "e".repeat(64), "e".repeat(64)));
            statement.execute("""
                    insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                        origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                        intent_key, side, position_effect, order_type, time_in_force,
                        requested_quantity, post_netting_quantity, final_quantity, decision,
                        decision_reason_code)
                    values ('%s', '%s', '%s', '%s', 'FLOW_EVALUATION', '%s', '%s', '%s', '%s',
                        'candidate:%s', 'BUY', 'OPEN_LONG', 'MARKET', 'DAY', 2, 2, 2, 'APPROVED',
                        'ELIGIBLE')
                    """.formatted(INTENT, BOT, INTENT_BATCH, ACCEPT_EVENT, EVALUATION, PARTITION,
                            FLOW, INSTRUMENT, CANDIDATE));
            statement.execute("""
                    insert into trading.order_intent_batches (id, bot_id, partition_id,
                        source_event_id, status, conflict_policy_hash, composition_rules_version,
                        input_state_hash, result_hash, finalized_at)
                    values ('%s', '%s', '%s', '%s', 'FINALIZED', '%s',
                        'order-intent-composition:v1', '%s', '%s', '2026-08-01T00:00:00+00')
                    """.formatted(LIQ_INTENT_BATCH, LIQ_BOT, LIQ_PARTITION, LIQ_ACCEPT_EVENT,
                            "e".repeat(64), "e".repeat(64), "e".repeat(64)));
            statement.execute("""
                    insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                        origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,
                        intent_key, side, position_effect, order_type, time_in_force,
                        requested_quantity, post_netting_quantity, final_quantity, decision,
                        decision_reason_code)
                    values ('%s', '%s', '%s', '%s', 'FLOW_EVALUATION', '%s', '%s', '%s', '%s',
                        'candidate:%s', 'BUY', 'OPEN_LONG', 'MARKET', 'DAY', 2, 2, 2, 'APPROVED',
                        'ELIGIBLE')
                    """.formatted(LIQ_INTENT, LIQ_BOT, LIQ_INTENT_BATCH, LIQ_ACCEPT_EVENT,
                            LIQ_EVALUATION, LIQ_PARTITION, LIQ_FLOW, INSTRUMENT, LIQ_CANDIDATE));
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed F91 parents", failure);
        }
    }

    @Autowired
    private PostgresOrderLifecycleStore orders;

    @Autowired
    private PostgresResourceReservationStore reservations;

    @Autowired
    private BotStopSettlementStore stopStore;

    @Autowired
    private CanonicalBotExecutionGate executionGate;

    @Autowired
    private PostgresOpenOrderCleanup orderCleanup;

    @Autowired
    private CandidateBatchProcessor candidateProcessor;

    @Autowired
    private com.idea2strategy.trading.persistence.stop.PostgresPositionLiquidation liquidation;

    @Autowired
    private com.idea2strategy.trading.persistence.fill.PostgresFillRecordStore fills;

    @Autowired
    private com.idea2strategy.trading.persistence.position.PostgresPositionLotStore lots;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    /**
     * One real stop, end to end: envelope in, canonical unwound state out, and a redelivery that
     * changes nothing.
     */
    @Test
    void aStopCommandUnwindsTheOpenOrderAndReservationDurably() {
        UUID orderId = placeOpenOrderWithActiveReservation();
        List<String> halts = new ArrayList<>();
        StrategyBotControlConsumer consumer = consumer(new StopSettlingBotLifecycle(
                orchestrator(StopStepResult.completed("no position to liquidate")),
                haltRecorder(halts),
                Clock.fixed(T0.plusSeconds(60), java.time.ZoneOffset.UTC)));

        BotControlResult first = consumer.consume(stopEnvelope(
                UUID.fromString("f91c0000-0000-4000-8000-000000000001"), "5".repeat(64)));
        BotControlResult redelivered = consumer.consume(stopEnvelope(
                UUID.fromString("f91c0000-0000-4000-8000-000000000001"), "5".repeat(64)));

        assertAll(
                () -> assertEquals(BotControlResult.STOPPED, first),
                () -> assertEquals(BotControlResult.DUPLICATE_IGNORED, redelivered),
                () -> assertEquals(List.of(BOT.toString()), halts),
                () -> assertEquals("CANCELLED", one(
                        "select cast(status as varchar) from trading.order_state_projections"
                                + " where order_id = ?", orderId)),
                () -> assertEquals("RELEASED", one(
                        "select cast(status as varchar) from trading.resource_reservations"
                                + " where intent_id = ?", INTENT)),
                () -> assertTrue(stopStore.findActive(BOT).isEmpty(),
                        "the settlement ran to its terminal checkpoint"));
    }

    /**
     * BLOCK_NEW_WORK from the outside: while the second bot's settlement is mid-flight, its scoped
     * candidate batches are refused at intake — before any claim exists for a recovery to race.
     */
    @Test
    void aScopedBatchIsRefusedWhileTheStopSettlementIsInFlight() {
        ConcurrentLinkedQueue<StopStepResult> liquidations = new ConcurrentLinkedQueue<>(List.of(
                StopStepResult.retryable("liquidation venue unavailable")));
        BotStopOrchestrator orchestrator = new BotStopOrchestrator(
                stopStore, executionGate, orderCleanup,
                (botId, operationId) -> {
                    StopStepResult next = liquidations.poll();
                    return next == null
                            ? StopStepResult.completed("no position to liquidate")
                            : next;
                });
        StopSettlingBotLifecycle lifecycle = new StopSettlingBotLifecycle(
                orchestrator, haltRecorder(new ArrayList<>()),
                Clock.fixed(T0.plusSeconds(120), java.time.ZoneOffset.UTC));

        lifecycle.stop(BLOCKED_BOT, "USER_REQUESTED");
        assertTrue(stopStore.findActive(BLOCKED_BOT).isPresent(), "stuck before liquidation");

        CandidateBatchProcessingResult blocked = candidateProcessor.process(scopedBatch());

        assertEquals(CandidateBatchProcessingResult.BLOCKED_BY_STOP, blocked);
        assertEquals(0L, (long) jdbc.sql(
                        "select count(*) from trading.candidate_batch_processing where batch_id = ?")
                .param(UUID.fromString("f91d0000-0000-4000-8000-00000000000d"))
                .query(Long.class).single());

        // The retryable step is retried to completion, exactly as the recovery worker would.
        orchestrator.resumeRecoverable(T0.plusSeconds(180));
        assertTrue(stopStore.findActive(BLOCKED_BOT).isEmpty());
    }

    /**
     * The liquidation step over a bot that actually holds something: the remainder becomes a
     * canonical system-origin intent, the close action names that intent, and asking for the stop
     * again converges on the same rows instead of selling twice.
     */
    @Test
    void aStopOfABotHoldingAPositionSubmitsOneLiquidationIntent() {
        openRealPosition();
        BotStopOrchestrator orchestrator = new BotStopOrchestrator(
                stopStore, executionGate, orderCleanup, liquidation);
        StopSettlingBotLifecycle lifecycle = new StopSettlingBotLifecycle(
                orchestrator, haltRecorder(new ArrayList<>()),
                Clock.fixed(T0.plusSeconds(300), java.time.ZoneOffset.UTC));

        lifecycle.stop(LIQ_BOT, "USER_REQUESTED");
        lifecycle.stop(LIQ_BOT, "USER_REQUESTED");

        var intent = jdbc.sql("""
                        select id, cast(side as varchar) as side,
                               cast(origin_type as varchar) as origin_type,
                               requested_quantity, decision_reason_code, evaluation_run_id
                        from trading.order_intents
                        where bot_id = ? and cast(origin_type as varchar) = 'SYSTEM_STOP_LIQUIDATION'
                        """)
                .param(LIQ_BOT)
                .query((rs, row) -> Map.of(
                        "id", rs.getObject("id", UUID.class).toString(),
                        "side", rs.getString("side"),
                        "quantity", rs.getBigDecimal("requested_quantity").stripTrailingZeros()
                                .toPlainString(),
                        "reason", rs.getString("decision_reason_code"),
                        "evaluation", String.valueOf(rs.getObject("evaluation_run_id"))))
                .list();
        long closeActions = jdbc.sql(
                        "select count(*) from trading.system_close_actions where bot_id = ?")
                .param(LIQ_BOT).query(Long.class).single();
        String actionIntent = jdbc.sql(
                        "select generated_intent_id from trading.system_close_actions where bot_id = ?")
                .param(LIQ_BOT).query((rs, row) -> rs.getObject(1, UUID.class).toString()).single();

        assertAll(
                () -> assertEquals(1, intent.size(), "one remainder, one liquidation intent"),
                () -> assertEquals("SELL", intent.getFirst().get("side")),
                () -> assertEquals("2", intent.getFirst().get("quantity")),
                () -> assertEquals("BOT_STOP", intent.getFirst().get("reason")),
                () -> assertEquals("null", intent.getFirst().get("evaluation"),
                        "no evaluation produced a system liquidation"),
                () -> assertEquals(1L, closeActions),
                () -> assertEquals(intent.getFirst().get("id"), actionIntent,
                        "the close action names the intent it generated"),
                () -> assertTrue(stopStore.findActive(LIQ_BOT).isEmpty()));
    }

    /** A real FIFO lot for the liquidation bot: order, whole fill and lot open, canonically. */
    private void openRealPosition() {
        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                LIQ_INTENT, LIQ_CANDIDATE, INSTRUMENT, OrderSide.BUY, new BigDecimal("2"),
                OrderType.MARKET, TimeInForce.DAY, null, null, null, null), T0);
        orders.createOrLoad(new OrderPlacement(
                lifecycle, LIQ_SCOPE,
                new OrderPolicyPins(FEE_POLICY, "broker-rules:v1", PRECISION,
                        "order-intent-composition:v1"),
                LIQ_ACCEPT_EVENT,
                List.of(new OrderComponent(LIQ_INTENT, new BigDecimal("2"), 1))));
        UUID componentId = jdbc.sql("select id from trading.order_components where order_id = ?")
                .param(lifecycle.orderId()).query(UUID.class).single();

        BigDecimal quantity = new BigDecimal("2");
        BigDecimal price = new BigDecimal("10");
        BigDecimal gross = quantity.multiply(price).setScale(8);
        BigDecimal fee = gross.multiply(new BigDecimal("0.002")).setScale(8);
        BigDecimal cash = gross.add(fee).negate();
        Instant filledAt = T0.plusSeconds(1);
        com.idea2strategy.trading.domain.fill.FillRecord record =
                com.idea2strategy.trading.domain.fill.FillRecord.original(
                        lifecycle.orderId(), "f91-liq-exec-1", quantity, price, fee,
                        new BigDecimal("0.01"), filledAt, filledAt);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    fills.appendOrLoad(new com.idea2strategy.trading.domain.fill.FillPosting(
                            record, LIQ_SCOPE, LIQ_FILL_EVENT, FEE_POLICY, 20, PRECISION, price,
                            filledAt, "m".repeat(64), gross, gross, cash, "fill-allocation:v1",
                            List.of(new com.idea2strategy.trading.domain.fill.FillAllocation(
                                    componentId, 1, quantity, gross, fee, cash))));
                    orders.apply(new com.idea2strategy.trading.application.order.FillOrderCommand(
                            UUID.randomUUID(), lifecycle.orderId(), LIQ_FILL_EVENT, 1, quantity,
                            filledAt));
                });
        UUID allocationId = jdbc.sql(
                        "select id from trading.fill_component_allocations where fill_id = ?")
                .param(record.fillRecordId()).query(UUID.class).single();
        lots.open(new com.idea2strategy.trading.domain.position.LotOpening(
                LIQ_SCOPE, LIQ_FLOW, INSTRUMENT, componentId, allocationId, LIQ_FILL_EVENT,
                com.idea2strategy.trading.domain.position.LotSide.LONG, quantity, gross, fee,
                filledAt));
    }

    // ---------------------------------------------------------------- fixtures

    private UUID placeOpenOrderWithActiveReservation() {
        OrderLifecycle lifecycle = new OrderLifecycleFactory().accepted(new OrderTerms(
                INTENT, CANDIDATE, INSTRUMENT, OrderSide.BUY, new BigDecimal("2"),
                OrderType.MARKET, TimeInForce.DAY, null, null, null, null), T0);
        orders.createOrLoad(new OrderPlacement(
                lifecycle, SCOPE, ORDER_PINS, ACCEPT_EVENT,
                List.of(new OrderComponent(INTENT, new BigDecimal("2"), 1))));
        UUID componentId = jdbc.sql("select id from trading.order_components where order_id = ?")
                .param(lifecycle.orderId()).query(UUID.class).single();

        ResourceReservation reserved = reservations.createOrLoad(new ReservationOpening(
                ResourceReservation.cash(INTENT, "USD", new BigDecimal("20.20"), T0),
                SCOPE, FLOW, RESERVE_EVENT,
                ReservationPolicyPins.buyingPower(BUFFER_POLICY, FEE_POLICY, PRECISION),
                ReservationPricing.buyingPower(
                        new BigDecimal("10"), T0, "m".repeat(64), new BigDecimal("20"),
                        new BigDecimal("0.01"), new BigDecimal("0.04"), new BigDecimal("0.15"))));
        reservations.attachToOrderComponent(
                new ReservationComponentLink(SCOPE, reserved.reservationId(), componentId));
        return lifecycle.orderId();
    }

    private BotStopOrchestrator orchestrator(StopStepResult liquidationResult) {
        return new BotStopOrchestrator(
                stopStore, executionGate, orderCleanup,
                (botId, operationId) -> liquidationResult);
    }

    private StrategyBotControlConsumer consumer(BotRuntimeLifecycle lifecycle) {
        return new StrategyBotControlConsumer(
                new StrategyBotContractCodec(),
                ignored -> Optional.of(compiledPlan()),
                new InMemoryCheckpoints(),
                lifecycle,
                // A stop command never reaches the warmup gate; a run command in this test would
                // be a bug, and this is where it fails loudly.
                (request, starter) -> {
                    throw new UnsupportedOperationException("no run command is delivered here");
                },
                new ExecutionPlanCompatibility(
                        "basic-compiled-plan.v1",
                        StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                        Map.of()));
    }

    private static BotRuntimeLifecycle haltRecorder(List<String> halts) {
        return new BotRuntimeLifecycle() {
            @Override
            public void start(LoadedExecutionPlan plan,
                com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup warmup,
                com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow window) {
                throw new UnsupportedOperationException("no run command is delivered here");
            }

            @Override
            public void stop(UUID botId, String reasonCode) {
                halts.add(botId.toString());
            }
        };
    }

    /** A real strategy-bot.v1 stop envelope; the codec, not the test, decides if it is valid. */
    private static StrategyBotOutboxEnvelope stopEnvelope(UUID messageId, String keyHex) {
        String key = "sha256:" + keyHex;
        String payload = """
                {"metadata":{"contractVersion":"strategy-bot.v1","messageType":"BOT_STOP_COMMAND",
                "messageId":"%s","occurredAt":"2026-08-01T14:31:00Z",
                "correlationId":"f91e0000-0000-4000-8000-000000000001","idempotencyKey":"%s"},
                "botId":"%s","expectedSnapshotHash":"sha256:%s","reasonCode":"USER_REQUESTED"}
                """.formatted(messageId, key, BOT, "1".repeat(64));
        return new StrategyBotOutboxEnvelope(
                messageId, "strategy-bot", BOT, 1, "BOT_STOP_COMMAND", "strategy-bot.v1", key, payload);
    }

    private static String compiledPlan() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",
                "elementCatalogVersion":"basic-elements:2026-07-31",
                "instrumentCatalogVersion":"us-supported-universe:2026-07-31","compilerVersion":"basic-compiler:1.0.0",
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt30m",
                "featureId":"%s","featureVersion":"1.0.0","instruments":["%s"],
                "resolution":"PT30M","requiredObservations":14}],"executionSnapshot":{"immutableStrategyVersion":{
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000","currency":"USD",
                "partitions":[{"key":"partition-1","budgetCapBps":10000,"flows":[{"key":"flow-1",
                "officialInstrumentIds":["%s"]}]}]},"steps":[{"sequence":1,"operation":"LOAD_FEATURE",
                "arguments":{"feature":"RSI_14","resolution":"30m"}},{"sequence":2,"operation":"COMPARE",
                "arguments":{"operator":"LT","threshold":"30"}},{"sequence":3,"operation":"EMIT_ORDER_CANDIDATE",
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],
                "planChecksum":"sha256:87837a0367ee346428f84d7eedf3a00289e10db105588eba84c7729e4731a4f6"}
                """.formatted("3".repeat(64), UUID.fromString("f91f0000-0000-4000-8000-000000000001"),
                        INSTRUMENT, "2".repeat(64), "1".repeat(64), INSTRUMENT);
    }

    private static CandidateBatch scopedBatch() {
        return new CandidateBatch(
                UUID.fromString("f91d0000-0000-4000-8000-00000000000d"),
                UUID.fromString("f91d0000-0000-4000-8000-00000000000e"),
                BLOCKED_BOT,
                BLOCKED_PARTITION,
                UUID.fromString("f91d0000-0000-4000-8000-00000000000f"),
                T0.plusSeconds(150),
                List.of(new CandidateOrder(
                        UUID.fromString("f91d0000-0000-4000-8000-000000000010"),
                        INSTRUMENT,
                        FLOW,
                        "BUY",
                        BigDecimal.ONE,
                        null,
                        List.of("BASIC_RULE_MATCHED"))));
    }

    private String one(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(String.class).single();
    }

    private static final class InMemoryCheckpoints implements BotControlCheckpointStore {
        private final Map<UUID, BotControlCheckpoint> checkpoints = new ConcurrentHashMap<>();

        @Override
        public Optional<BotControlCheckpoint> find(UUID botId) {
            return Optional.ofNullable(checkpoints.get(botId));
        }

        @Override
        public void save(BotControlCheckpoint checkpoint) {
            checkpoints.put(checkpoint.botId(), checkpoint);
        }
    }
}
