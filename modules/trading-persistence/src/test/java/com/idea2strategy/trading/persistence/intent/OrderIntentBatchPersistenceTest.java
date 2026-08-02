package com.idea2strategy.trading.persistence.intent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.intent.OrderIntentBatchConflictException;
import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the order intent write path against the real canonical schema.
 *
 * <p>Before this, F04 wrote to a private compatibility table, so the F15 read projections could
 * never see a real evaluation result. These tests assert the canonical columns directly rather than
 * only round tripping the aggregate, because landing in the right shape is the whole point.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderIntentBatchPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PARTITION = UUID.fromString("11000000-0000-4000-8000-000000000002");
    private static final UUID FLOW = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_FLOW = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID EVENT = UUID.fromString("13000000-0000-4000-8000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("14000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_EVALUATION = UUID.fromString("14000000-0000-4000-8000-000000000002");
    private static final UUID CANDIDATE_BATCH = UUID.fromString("15000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT = UUID.fromString("16000000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE_ONE = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID CANDIDATE_TWO = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final Instant AT = Instant.parse("2026-08-02T09:00:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbcClient;
    private static JdbcTransactionManager transactionManager;
    private static PostgresOrderIntentBatchStore store;
    private static JooqOrderIntentBatchQuery query;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqOrderIntentBatchQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        // Bots, partitions, flows, evaluations and instruments belong to other services. They are
        // seeded with referential triggers off exactly as the canonical contract fixtures do, on one
        // connection because session_replication_role is session state. The intent inserts under test
        // then run with the triggers back on, so their own foreign keys are genuinely checked.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'a0000000-0000-4000-8000-000000000001', 'BASIC', 'Intent bot',
                        'RUNNING', '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00',
                        '2026-08-02T09:00:00+00')
                    """.formatted(BOT));
            for (UUID partition : List.of(PARTITION, OTHER_PARTITION)) {
                statement.addBatch("""
                        insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                            position_x, position_y, configuration_hash)
                        values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                        """.formatted(partition, BOT, "c".repeat(64)));
            }
            statement.addBatch(flow(FLOW, PARTITION));
            statement.addBatch(flow(OTHER_FLOW, OTHER_PARTITION));
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('%s', '%s', 1, 'EVALUATION_COMPLETED', 'v1', gen_random_uuid(),
                        'seed-event-1', '2026-08-02T09:00:00+00', '2026-08-02T09:00:00+00', '{}')
                    """.formatted(EVENT, BOT));
            statement.addBatch(evaluationRun(EVALUATION, PARTITION, FLOW));
            statement.addBatch(evaluationRun(OTHER_EVALUATION, OTHER_PARTITION, OTHER_FLOW));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    private static String flow(UUID flowId, UUID partitionId) {
        return """
                insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                    compiled_flow_plan_id, position_x, position_y, semantic_document,
                    layout_document, layout_schema_version, semantic_hash, layout_hash,
                    configuration_hash)
                values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                    '{}', '{}', 'v1', '%s', '%s', '%s')
                """.formatted(flowId, partitionId, "a".repeat(64), "b".repeat(64), "c".repeat(64));
    }

    /**
     * Left RUNNING on purpose. The canonical {@code evaluation_success_complete} CHECK demands a
     * completion time and result hash from a SUCCEEDED run, and CHECK constraints stay armed under
     * {@code session_replication_role = replica}; only triggers and foreign keys are lifted.
     */
    private static String evaluationRun(UUID id, UUID partitionId, UUID flowId) {
        return """
                insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                    trigger_event_id, status, queued_at)
                values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-08-02T09:00:00+00')
                """.formatted(id, BOT, partitionId, flowId, EVENT);
    }

    @BeforeEach
    void clearCanonicalIntents() {
        jdbcClient.sql("delete from trading.order_intents").update();
        jdbcClient.sql("delete from trading.order_intent_batches").update();
    }

    @Test
    void anApprovedEvaluationLandsInTheCanonicalShape() {
        OrderIntentBatch desired = batch(EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE)));

        assertEquals(desired, store.createOrLoad(desired));

        OrderIntentBatchPersistenceView stored = query.findBatch(desired.batchId()).orElseThrow();
        var intent = stored.intents().getFirst();
        assertAll(
                () -> assertEquals("FINALIZED", stored.status()),
                () -> assertEquals(BOT, stored.botId()),
                () -> assertEquals(PARTITION, stored.partitionId()),
                () -> assertEquals(EVENT, stored.sourceEventId()),
                () -> assertEquals(AT, stored.finalizedAt()),
                () -> assertEquals(
                        OrderIntentBatchFactory.COMPOSITION_RULES_VERSION,
                        stored.compositionRulesVersion()),
                () -> assertEquals("candidate:" + CANDIDATE_ONE, intent.intentKey()),
                () -> assertEquals("FLOW_EVALUATION", intent.originType()),
                () -> assertEquals(EVALUATION, intent.evaluationRunId()),
                () -> assertEquals(FLOW, intent.flowId()),
                () -> assertEquals(INSTRUMENT, intent.instrumentId()),
                () -> assertEquals("BUY", intent.side()),
                () -> assertEquals("OPEN_LONG", intent.positionEffect()),
                () -> assertEquals("MARKET", intent.orderType()),
                () -> assertEquals("DAY", intent.timeInForce()),
                () -> assertEquals("APPROVED", intent.decision()),
                () -> assertEquals(0, new BigDecimal("3").compareTo(intent.requestedQuantity())),
                () -> assertEquals(0, new BigDecimal("3").compareTo(intent.postNettingQuantity())));
    }

    @Test
    void redeliveringIdenticalWorkReturnsTheStoredBatchInsteadOfASecond() {
        OrderIntentBatch desired = batch(
                EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE), approved(CANDIDATE_TWO)));

        assertEquals(desired, store.createOrLoad(desired));
        assertEquals(desired, newStore().createOrLoad(desired));
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countIntents());
    }

    @Test
    void concurrentIdenticalRequestsConvergeOnOneCompleteBatch() throws Exception {
        OrderIntentBatch desired = batch(
                EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE), approved(CANDIDATE_TWO)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<OrderIntentBatch> create = () -> {
            ready.countDown();
            start.await();
            return newStore().createOrLoad(desired);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertTrue(ready.await(10, SECONDS));
            start.countDown();

            assertEquals(desired, first.get(30, SECONDS));
            assertEquals(desired, second.get(30, SECONDS));
        }
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countIntents());
    }

    @Test
    void anEvaluationThatDecidedDifferentlyIsAConflictInsteadOfAnOverwrite() {
        store.createOrLoad(batch(EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE))));

        OrderIntentBatch divergent = batch(
                EVALUATION, PARTITION, FLOW, List.of(rejected(CANDIDATE_ONE)));

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(divergent));
        assertEquals(1, query.countIntents());
        assertEquals(
                "APPROVED",
                query.findBatch(divergent.batchId()).orElseThrow().intents().getFirst().decision());
    }

    @Test
    void aMissingIntentRowIsAConflictRatherThanAnAcceptedReplay() {
        OrderIntentBatch desired = batch(
                EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE), approved(CANDIDATE_TWO)));
        store.createOrLoad(desired);
        jdbcClient.sql("delete from trading.order_intents where intent_key = :key")
                .param("key", "candidate:" + CANDIDATE_TWO)
                .update();

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(desired));
    }

    /**
     * The canonical batch row carries no evaluation column, so the store trusts the derived primary
     * key for it. This is the guard that keeps that shortcut honest.
     */
    @Test
    void intentsNamingAnotherEvaluationAreAConflict() {
        OrderIntentBatch desired = batch(EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE)));
        store.createOrLoad(desired);
        jdbcClient.sql("update trading.order_intents set evaluation_run_id = :other")
                .param("other", OTHER_EVALUATION)
                .update();

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(desired));
    }

    @Test
    void anEvaluationThatProducedNoCandidateStillFinalizes() {
        OrderIntentBatch desired = batch(EVALUATION, PARTITION, FLOW, List.of());

        assertEquals(desired, store.createOrLoad(desired));
        assertEquals(desired, newStore().createOrLoad(desired));
        assertEquals(1, query.countBatches());
        assertEquals(0, query.countIntents());
        assertEquals("FINALIZED", query.findBatch(desired.batchId()).orElseThrow().status());
    }

    @Test
    void aReducedIntentKeepsTheRequestedAmountAndOrdersOnlyWhatSurvived() {
        OrderIntentBatch desired = batch(
                EVALUATION, PARTITION, FLOW,
                List.of(new OrderIntentRequest(
                        CANDIDATE_ONE, FLOW, INSTRUMENT, OrderSide.BUY,
                        OrderPositionEffect.INCREASE_LONG, OrderType.MARKET, TimeInForce.DAY,
                        new BigDecimal("10"), null, null, null,
                        IntentDecision.REDUCED, "BUDGET_PRORATED", new BigDecimal("4"))));
        store.createOrLoad(desired);

        var intent = query.findBatch(desired.batchId()).orElseThrow().intents().getFirst();
        assertAll(
                () -> assertEquals("REDUCED", intent.decision()),
                () -> assertEquals(0, new BigDecimal("10").compareTo(intent.requestedQuantity())),
                () -> assertEquals(0, new BigDecimal("4").compareTo(intent.finalQuantity())),
                () -> assertEquals(0, new BigDecimal("4").compareTo(intent.postNettingQuantity())));
    }

    @Test
    void aRejectedIntentLeavesNothingToOrder() {
        OrderIntentBatch desired = batch(EVALUATION, PARTITION, FLOW, List.of(rejected(CANDIDATE_ONE)));
        store.createOrLoad(desired);

        var intent = query.findBatch(desired.batchId()).orElseThrow().intents().getFirst();
        assertAll(
                () -> assertEquals("REJECTED", intent.decision()),
                () -> assertEquals("RISK_LIMIT_EXCEEDED", intent.decisionReasonCode()),
                () -> assertEquals(null, intent.finalQuantity()),
                () -> assertEquals(0, BigDecimal.ZERO.compareTo(intent.postNettingQuantity())));
    }

    /**
     * The canonical unique index is on {@code (bot_id, partition_id, source_event_id)}, so one
     * official event fans out into one batch per partition rather than colliding on the event.
     */
    @Test
    void eachPartitionOfOneEventGetsItsOwnBatch() {
        OrderIntentBatch mine = batch(EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE)));
        OrderIntentBatch theirs = batch(
                OTHER_EVALUATION, OTHER_PARTITION, OTHER_FLOW, List.of(approved(CANDIDATE_TWO)));

        store.createOrLoad(mine);
        store.createOrLoad(theirs);

        assertNotEquals(mine.batchId(), theirs.batchId());
        assertEquals(2, query.countBatches());
        assertEquals(EVENT, query.findBatch(mine.batchId()).orElseThrow().sourceEventId());
        assertEquals(EVENT, query.findBatch(theirs.batchId()).orElseThrow().sourceEventId());
    }

    @Test
    void candidateArrivalOrderDoesNotChangeWhatIsStored() {
        OrderIntentBatch forward = batch(
                EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE), approved(CANDIDATE_TWO)));
        OrderIntentBatch reversed = batch(
                EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_TWO), approved(CANDIDATE_ONE)));

        assertEquals(forward, reversed, "candidate order must not change batch identity");
        store.createOrLoad(forward);
        assertEquals(forward, newStore().createOrLoad(reversed));
        assertEquals(2, query.countIntents());
    }

    @Test
    void unexpectedSchemaFailureRemainsDataAccessException() {
        OrderIntentBatch desired = batch(EVALUATION, PARTITION, FLOW, List.of(approved(CANDIDATE_ONE)));
        TransactionTemplate ddlTransaction = new TransactionTemplate(transactionManager);

        assertThrows(DataAccessException.class, () -> ddlTransaction.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            alter table trading.order_intent_batches
                            rename to order_intent_batches_unavailable
                            """)
                    .update();
            store.createOrLoad(desired);
        }));

        assertEquals(0, query.countBatches());
    }

    private static OrderIntentRequest approved(UUID candidateId) {
        return new OrderIntentRequest(
                candidateId, FLOW, INSTRUMENT, OrderSide.BUY, OrderPositionEffect.INCREASE_LONG,
                OrderType.MARKET, TimeInForce.DAY, new BigDecimal("3"), null, null, null,
                IntentDecision.APPROVED, "ELIGIBLE", new BigDecimal("3"));
    }

    private static OrderIntentRequest rejected(UUID candidateId) {
        return new OrderIntentRequest(
                candidateId, FLOW, INSTRUMENT, OrderSide.BUY, OrderPositionEffect.INCREASE_LONG,
                OrderType.MARKET, TimeInForce.DAY, new BigDecimal("3"), null, null, null,
                IntentDecision.REJECTED, "RISK_LIMIT_EXCEEDED", null);
    }

    private static OrderIntentBatch batch(
            UUID evaluationId, UUID partitionId, UUID flowId, List<OrderIntentRequest> intents) {
        List<OrderIntentRequest> scoped = intents.stream()
                .map(intent -> new OrderIntentRequest(
                        intent.candidateId(), flowId, intent.instrumentId(), intent.side(),
                        intent.positionEffect(), intent.orderType(), intent.timeInForce(),
                        intent.requestedQuantity(), intent.limitPrice(), intent.stopPrice(),
                        intent.requestedExpiresAt(), intent.decision(), intent.decisionReasonCode(),
                        intent.finalQuantity()))
                .toList();
        return new OrderIntentBatchFactory().create(new OrderIntentBatchRequest(
                BOT, partitionId, EVENT, evaluationId, CANDIDATE_BATCH, AT, scoped));
    }

    private static PostgresOrderIntentBatchStore newStore() {
        return new PostgresOrderIntentBatchStore(JdbcClient.create(dataSource), transactionManager);
    }
}
