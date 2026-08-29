package com.idea2strategy.trading.persistence.stop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.port.BotExecutionGatePort;
import com.idea2strategy.trading.application.port.OpenOrderCleanupPort;
import com.idea2strategy.trading.application.port.PositionLiquidationPort;
import com.idea2strategy.trading.application.stop.BotStopOrchestrator;
import com.idea2strategy.trading.application.stop.RequestBotStopCommand;
import com.idea2strategy.trading.application.stop.StopStepResult;
import com.idea2strategy.trading.domain.stop.BotStopSettlement;
import com.idea2strategy.trading.domain.stop.StopCheckpoint;
import com.idea2strategy.trading.domain.stop.StopReason;
import com.idea2strategy.trading.domain.stop.StopStep;
import com.idea2strategy.trading.domain.stop.SystemCloseAction;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.event.PostgresBotEventStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the bot stop settlement write path against the real canonical schema.
 *
 * <p>Canonical storage has no settlement table, so the procedure is written to
 * {@code bot.bot_events} and the forced closes it generates to
 * {@code trading.system_close_actions}. What has to be proven is therefore not that a row exists
 * but that the event stream still behaves the way the private tables did: one settlement per bot, a
 * checkpoint that survives a restart, a step that cannot be applied twice, a version that cannot be
 * claimed twice, and a forced close that stands or falls with the checkpoint that caused it.
 */
@Testcontainers(disabledWithoutDocker = true)
class BotStopSettlementPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID FLOW = UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_A = UUID.fromString("33000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_B = UUID.fromString("33000000-0000-4000-8000-000000000002");
    private static final UUID SEED_EVENT = UUID.fromString("34000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_BATCH = UUID.fromString("35000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_A = UUID.fromString("36000000-0000-4000-8000-000000000001");
    private static final UUID INTENT_B = UUID.fromString("36000000-0000-4000-8000-000000000002");

    private static final Instant T0 = Instant.parse("2026-08-02T03:00:00Z");
    private static final String HASH = "c".repeat(64);
    private static final String SETTLEMENT_EVENT_TYPES =
            "'SETTLEMENT_REQUESTED', 'SETTLEMENT_STEP_RECORDED', 'SETTLEMENT_COMPLETED', "
                    + "'SETTLEMENT_FAILED'";

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static JooqBotStopSettlementQuery query;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        query = new JooqBotStopSettlementQuery(DSL.using(dataSource, SQLDialect.POSTGRES));

        // bot.bots, bot.bot_partitions, bot.flows and market_data.instruments belong to other
        // services; seeded with referential triggers off exactly as the canonical contract fixtures
        // do. Every write under test runs with them back on.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            for (UUID bot : List.of(BOT, OTHER_BOT)) {
                statement.addBatch("""
                        insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                            lifecycle_changed_at, created_at, execution_eligible_from)
                        values ('%s', 'a3000000-0000-4000-8000-000000000001', 'BASIC', 'Stop bot',
                            'RUNNING', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00',
                            '2026-08-01T00:00:00+00')
                        """.formatted(bot));
            }
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 5000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, HASH));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(FLOW, PARTITION, HASH, HASH, HASH));
            for (UUID instrument : List.of(INSTRUMENT_A, INSTRUMENT_B)) {
                statement.addBatch("""
                        insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                            currency_code)
                        values ('%s', 'STOCK', 'XNAS', 'USD')
                        """.formatted(instrument));
            }
            // The cause of the seeded intents. It is not a settlement event, so the per-test clear
            // leaves it alone and the intents keep a valid parent.
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('%s', '%s', 1, 'SEED', 'v1', gen_random_uuid(), 'stop-seed',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00', '{}')
                    """.formatted(SEED_EVENT, BOT));
            statement.addBatch("""
                    insert into trading.order_intent_batches (id, bot_id, partition_id,
                        source_event_id, status, conflict_policy_hash, composition_rules_version,
                        input_state_hash, result_hash, finalized_at)
                    values ('%s', '%s', '%s', '%s', 'FINALIZED', '%s',
                        'order-intent-composition:v1', '%s', '%s', '2026-08-01T00:00:00+00')
                    """.formatted(INTENT_BATCH, BOT, PARTITION, SEED_EVENT, HASH, HASH, HASH));
            // The liquidation intents a stop settlement is evidence for. SYSTEM_STOP_LIQUIDATION is
            // the canonical origin for exactly this, and it forbids an evaluation run.
            statement.addBatch(intent(INTENT_A, INSTRUMENT_A, "stop-liquidation-a"));
            statement.addBatch(intent(INTENT_B, INSTRUMENT_B, "stop-liquidation-b"));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        }
    }

    private static String intent(UUID intentId, UUID instrumentId, String key) {
        return """
                insert into trading.order_intents (id, bot_id, batch_id, source_event_id,
                    origin_type, partition_id, flow_id, instrument_id, intent_key, side,
                    position_effect, order_type, time_in_force, requested_quantity, decision,
                    decision_reason_code)
                values ('%s', '%s', '%s', '%s', 'SYSTEM_STOP_LIQUIDATION', '%s', '%s', '%s', '%s',
                    'SELL', 'CLOSE_LONG', 'MARKET', 'DAY', 4, 'APPROVED', 'BOT_STOP_LIQUIDATION')
                """.formatted(intentId, BOT, INTENT_BATCH, SEED_EVENT, PARTITION, FLOW,
                instrumentId, key);
    }

    /**
     * Only the settlement events are removed. The seeded cause of the order intents is not one of
     * them, so the intents keep the parent their foreign key needs.
     */
    @BeforeEach
    void clearSettlementEvents() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set constraints all deferred");
            statement.addBatch("delete from trading.system_close_actions");
            statement.addBatch(
                    "delete from bot.bot_events where event_type in (" + SETTLEMENT_EVENT_TYPES + ")");
            statement.addBatch("update bot.bots set lifecycle_status = 'STOPPING', stopped_at = null, "
                    + "lifecycle_changed_at = '2026-08-01T00:00:00+00', "
                    + "updated_at = '2026-08-01T00:00:00+00'");
            statement.executeBatch();
            connection.commit();
        }
    }

    @Test
    void everyCheckpointIsAnEventAndAPartialLiquidationResumesAfterRestart() {
        Queue<StopStepResult> liquidation = new ArrayDeque<>();
        liquidation.add(StopStepResult.partial(
                "one position remains", List.of(closeAction(INTENT_A, INSTRUMENT_A, "3"))));
        liquidation.add(StopStepResult.completed(
                "flat", List.of(closeAction(INTENT_B, INSTRUMENT_B, "1"))));
        BotStopOrchestrator first = orchestrator(
                (id, op) -> StopStepResult.completed("blocked"),
                (id, op) -> StopStepResult.completed("cancelled and released"),
                (id, op) -> liquidation.remove());

        BotStopSettlement pending = first.requestStop(new RequestBotStopCommand(
                BOT, StopReason.ACCOUNT_SUSPENDED, "account suspended", T0));
        assertEquals(StopCheckpoint.LIQUIDATING, pending.checkpoint());

        // A new store on a new orchestrator: nothing carries over in memory, so the checkpoint has
        // to come back out of the canonical stream.
        BotStopOrchestrator restarted = orchestrator(
                (id, op) -> { throw new AssertionError("the gate must not replay"); },
                (id, op) -> { throw new AssertionError("cleanup must not replay"); },
                (id, op) -> liquidation.remove());
        BotStopSettlement stopped = restarted.resumeRecoverable(T0.plusSeconds(1)).getFirst();

        List<JooqBotStopSettlementQuery.AttemptView> attempts = query.attempts(stopped.settlementId());
        List<JooqBotStopSettlementQuery.CloseActionView> closes = query.closeActions(BOT);
        assertAll(
                () -> assertEquals(StopCheckpoint.STOPPED, stopped.checkpoint()),
                () -> assertEquals(5, stopped.version()),
                () -> assertEquals(stopped, query.find(stopped.settlementId()).orElseThrow().toDomain()),
                () -> assertEquals(4, attempts.size(), "one attempt per transition, the request aside"),
                () -> assertEquals(
                        List.of("REQUESTED", "WORK_BLOCKED", "ORDERS_CLEANED", "LIQUIDATING"),
                        attempts.stream()
                                .map(JooqBotStopSettlementQuery.AttemptView::fromCheckpoint).toList(),
                        "the chain of checkpoints the private event table held"),
                () -> assertEquals(
                        List.of("COMPLETED", "COMPLETED", "PARTIAL", "COMPLETED"),
                        attempts.stream()
                                .map(JooqBotStopSettlementQuery.AttemptView::status).toList()),
                () -> assertEquals(
                        stopped.operationId(StopStep.LIQUIDATE_POSITIONS),
                        attempts.getLast().operationId(),
                        "the operation identifier stays derived from the settlement and the step"),
                () -> assertEquals(
                        List.of("SETTLEMENT_REQUESTED", "SETTLEMENT_STEP_RECORDED",
                                "SETTLEMENT_STEP_RECORDED", "SETTLEMENT_STEP_RECORDED",
                                "SETTLEMENT_COMPLETED"),
                        eventTypes(),
                        "the terminal checkpoint is its own canonical event type"),
                () -> assertEquals(2, closes.size(), "one forced close per instrument"),
                () -> assertEquals("BOT_STOP", closes.getFirst().reasonType()),
                () -> assertEquals(INTENT_A, closes.getFirst().generatedIntentId()),
                () -> assertEquals(new BigDecimal("3.00000000"), closes.getFirst().requestedQuantity()),
                () -> assertTrue(
                        causationChainIsUnbroken(), "every settlement event names the one before it"),
                () -> assertEquals(
                        Set.of(stopped.settlementId()), correlationIds(),
                        "the settlement identifier correlates its whole stream"),
                () -> assertEquals("STOPPED", lifecycleStatus(BOT)),
                () -> assertEquals(T0.plusSeconds(1), stoppedAt(BOT)));
    }

    @Test
    void redeliveringTheRequestAndTheStepLeavesOneEventEach() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement desired =
                BotStopSettlement.request(BOT, StopReason.POLICY_FORCED, "policy", T0);

        BotStopSettlement first = store.createOrLoad(desired);
        BotStopSettlement again = store.createOrLoad(desired);
        StopStepResult blocked = StopStepResult.completed("blocked");
        BotStopSettlement advanced =
                store.recordStep(first, StopStep.BLOCK_NEW_WORK, blocked, T0.plusSeconds(1));
        // The redelivery presents the state the caller acted on, which the settlement has moved past.
        BotStopSettlement replayed =
                store.recordStep(first, StopStep.BLOCK_NEW_WORK, blocked, T0.plusSeconds(1));

        assertAll(
                () -> assertEquals(first, again),
                () -> assertEquals(1L, first.version()),
                () -> assertEquals(advanced, replayed),
                () -> assertEquals(StopCheckpoint.WORK_BLOCKED, replayed.checkpoint()),
                () -> assertEquals(2, settlementEventCount()),
                () -> assertEquals(1, query.attempts(first.settlementId()).size()));
    }

    @Test
    void aRedeliveredLiquidationDoesNotDuplicateTheForcedClose() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement settlement = advanceToLiquidation(store);
        StopStepResult flat =
                StopStepResult.completed("flat", List.of(closeAction(INTENT_A, INSTRUMENT_A, "2")));

        BotStopSettlement stopped =
                store.recordStep(settlement, StopStep.LIQUIDATE_POSITIONS, flat, T0.plusSeconds(3));
        BotStopSettlement replayed =
                store.recordStep(settlement, StopStep.LIQUIDATE_POSITIONS, flat, T0.plusSeconds(3));

        assertAll(
                () -> assertEquals(stopped, replayed),
                () -> assertEquals(StopCheckpoint.STOPPED, stopped.checkpoint()),
                () -> assertEquals(1, query.closeActions(BOT).size()),
                () -> assertEquals(
                        stopped.settlementId(),
                        query.find(stopped.settlementId()).orElseThrow().settlementId()));
    }

    @Test
    void aSecondDifferentStopRequestForOneBotJoinsTheSettlementAlreadyRunning() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement running = store.createOrLoad(
                BotStopSettlement.request(BOT, StopReason.USER_REQUEST, "owner asked", T0));
        BotStopSettlement competing = BotStopSettlement.request(
                BOT, StopReason.POLICY_FORCED, "policy engine", T0.plusSeconds(30));

        BotStopSettlement resolved = store.createOrLoad(competing);

        assertAll(
                () -> assertNotEquals(competing.settlementId(), resolved.settlementId()),
                () -> assertEquals(running, resolved, "one settlement per bot, as bot_id unique was"),
                () -> assertEquals(StopReason.USER_REQUEST, resolved.reason()),
                () -> assertEquals(1, settlementEventCount()));
    }

    @Test
    void twoStepsClaimingTheSameVersionResolveToOneCanonicalTransition() throws Exception {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement settlement = store.createOrLoad(
                BotStopSettlement.request(BOT, StopReason.USER_REQUEST, "race", T0));

        // Both callers act on version 1 and both derive the key for version 2. Canonical uniqueness
        // on (bot_id, idempotency_key) is what settles it, not a version column.
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<BotStopSettlement>> work = new ArrayList<>();
        for (String detail : List.of("blocked by the worker", "blocked by the recovery poll")) {
            work.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                return store.recordStep(settlement, StopStep.BLOCK_NEW_WORK,
                        StopStepResult.completed(detail), T0.plusSeconds(1));
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<BotStopSettlement> results = new ArrayList<>();
        try {
            List<Future<BotStopSettlement>> futures = new ArrayList<>();
            work.forEach(task -> futures.add(pool.submit(task)));
            start.countDown();
            for (Future<BotStopSettlement> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertAll(
                () -> assertEquals(1, new HashSet<>(results).size(), "the callers disagree on the state"),
                () -> assertEquals(StopCheckpoint.WORK_BLOCKED, results.getFirst().checkpoint()),
                () -> assertEquals(2L, results.getFirst().version()),
                () -> assertEquals(2, settlementEventCount(), "a version was claimed twice"),
                () -> assertEquals(1, query.attempts(settlement.settlementId()).size()));
    }

    @Test
    void aForcedCloseWithoutItsIntentRollsBackTheSettlementEventToo() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement settlement = advanceToLiquidation(store);
        SystemCloseAction unbacked = SystemCloseAction.forBotStop(
                BOT, PARTITION, FLOW, INSTRUMENT_A, new BigDecimal("2"), UUID.randomUUID(),
                "{\"trigger\":\"bot stop\"}", HASH);

        assertThrows(DataIntegrityViolationException.class, () -> store.recordStep(
                settlement, StopStep.LIQUIDATE_POSITIONS,
                StopStepResult.completed("flat", List.of(unbacked)), T0.plusSeconds(3)));

        // The canonical rule is that the close and the checkpoint that caused it are one unit. A
        // settlement event surviving here would claim a liquidation the database refused.
        BotStopSettlement reloaded = store.load(settlement.settlementId());
        assertAll(
                () -> assertEquals(StopCheckpoint.ORDERS_CLEANED, reloaded.checkpoint()),
                () -> assertEquals(settlement.version(), reloaded.version()),
                () -> assertEquals(0, query.closeActions(BOT).size()),
                () -> assertEquals(3, settlementEventCount()));
    }

    @Test
    void aForcedCloseIsRefusedOutsideTheLiquidationStep() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement settlement = store.createOrLoad(
                BotStopSettlement.request(BOT, StopReason.USER_REQUEST, "misattributed", T0));
        StopStepResult blocked = StopStepResult.completed(
                "blocked", List.of(closeAction(INTENT_A, INSTRUMENT_A, "1")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.recordStep(settlement, StopStep.BLOCK_NEW_WORK, blocked, T0.plusSeconds(1)));

        assertTrue(failure.getMessage().contains("LIQUIDATE_POSITIONS"));
        assertEquals(1, settlementEventCount());
    }

    @Test
    void onlyNonTerminalSettlementsStayRecoverable() {
        PostgresBotStopSettlementStore store = store();
        BotStopSettlement mine = store.createOrLoad(
                BotStopSettlement.request(BOT, StopReason.USER_REQUEST, "mine", T0));
        BotStopSettlement theirs = store.createOrLoad(BotStopSettlement.request(
                OTHER_BOT, StopReason.POLICY_FORCED, "theirs", T0.plusSeconds(5)));

        assertEquals(
                List.of(mine.settlementId(), theirs.settlementId()),
                store.loadRecoverable().stream().map(BotStopSettlement::settlementId).toList(),
                "oldest request first");

        BotStopSettlement failed = store.recordStep(mine, StopStep.BLOCK_NEW_WORK,
                StopStepResult.terminalFailure("manual intervention required"), T0.plusSeconds(1));

        assertAll(
                () -> assertEquals(StopCheckpoint.SETTLEMENT_FAILED, failed.checkpoint()),
                () -> assertEquals(StopStep.BLOCK_NEW_WORK, failed.failedStep()),
                () -> assertEquals("manual intervention required", failed.terminalReason()),
                () -> assertEquals(
                        List.of(theirs.settlementId()),
                        store.loadRecoverable().stream().map(BotStopSettlement::settlementId).toList()),
                () -> assertTrue(eventTypes().contains("SETTLEMENT_FAILED"),
                        "the failure is the canonical event type the note names"),
                () -> assertEquals(failed, query.find(failed.settlementId()).orElseThrow().toDomain()));
    }

    private static BotStopSettlement advanceToLiquidation(PostgresBotStopSettlementStore store) {
        BotStopSettlement requested = store.createOrLoad(
                BotStopSettlement.request(BOT, StopReason.USER_REQUEST, "owner asked", T0));
        BotStopSettlement blocked = store.recordStep(requested, StopStep.BLOCK_NEW_WORK,
                StopStepResult.completed("blocked"), T0.plusSeconds(1));
        return store.recordStep(blocked, StopStep.CANCEL_ORDERS_AND_RELEASE,
                StopStepResult.completed("cancelled and released"), T0.plusSeconds(2));
    }

    private static SystemCloseAction closeAction(UUID intentId, UUID instrumentId, String quantity) {
        return SystemCloseAction.forBotStop(BOT, PARTITION, FLOW, instrumentId,
                new BigDecimal(quantity), intentId, "{\"trigger\":\"bot stop\"}", HASH);
    }

    private static BotStopOrchestrator orchestrator(
            BotExecutionGatePort gate, OpenOrderCleanupPort cleanup, PositionLiquidationPort liquidation) {
        return new BotStopOrchestrator(store(), gate, cleanup, liquidation);
    }

    private static PostgresBotStopSettlementStore store() {
        return new PostgresBotStopSettlementStore(
                new PostgresBotEventStore(jdbc, transactions), jdbc, transactions);
    }

    private static List<String> eventTypes() {
        return jdbc.sql("select event_type from bot.bot_events where event_type in ("
                        + SETTLEMENT_EVENT_TYPES + ") order by event_sequence")
                .query(String.class).list();
    }

    private static Set<UUID> correlationIds() {
        return new HashSet<>(jdbc.sql(
                        "select distinct correlation_id from bot.bot_events where event_type in ("
                                + SETTLEMENT_EVENT_TYPES + ")")
                .query(UUID.class).list());
    }

    private static boolean causationChainIsUnbroken() {
        return jdbc.sql("""
                select count(*) from bot.bot_events child
                where child.event_type in ('SETTLEMENT_STEP_RECORDED', 'SETTLEMENT_COMPLETED',
                                           'SETTLEMENT_FAILED')
                  and not exists (
                      select 1 from bot.bot_events parent
                      where parent.id = child.causation_event_id
                        and parent.correlation_id = child.correlation_id)
                """).query(Integer.class).single() == 0;
    }

    private static int settlementEventCount() {
        return jdbc.sql("select count(*) from bot.bot_events where event_type in ("
                + SETTLEMENT_EVENT_TYPES + ")").query(Integer.class).single();
    }

    private static String lifecycleStatus(UUID botId) {
        return jdbc.sql("select lifecycle_status::text from bot.bots where id = :bot")
                .param("bot", botId)
                .query(String.class)
                .single();
    }

    private static Instant stoppedAt(UUID botId) {
        return jdbc.sql("select stopped_at from bot.bots where id = :bot")
                .param("bot", botId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
}
