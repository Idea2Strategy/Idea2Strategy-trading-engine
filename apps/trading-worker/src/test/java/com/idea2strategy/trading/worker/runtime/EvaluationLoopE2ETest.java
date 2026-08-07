package com.idea2strategy.trading.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.candidate.CandidateBatchProcessingResult;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.worker.candidate.OrderCandidateBatchAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * RT2 end to end: a started bot, a market event, and a canonical order.
 *
 * <p>Every piece of this existed and none of it was joined up. Here a real locked plan is interpreted,
 * a real warm-up seeds the feature window, a real market event drives the incremental runtime, the
 * Basic executor decides, the converger accepts, and the resulting version 3 batch — a share, not a
 * quantity — is composed into canonical intents, orders and reservations against a real PostgreSQL.
 *
 * <p>The warm-up matters to the outcome, not just to the wiring: RSI_14 needs fifteen completed bars,
 * so a runtime started with nothing would decide INPUT_MISSING on this event instead of buying. The
 * seeded window is fourteen falling closes, which puts the RSI below the plan's threshold of 30, and
 * the live event continues the fall.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class EvaluationLoopE2ETest {

    private static final UUID BOT = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final UUID RELEASE = UUID.fromString("c2000000-0000-4000-8000-000000000002");
    private static final UUID PARTITION = UUID.fromString("c2000000-0000-4000-8000-000000000003");
    private static final UUID FLOW = UUID.fromString("c2000000-0000-4000-8000-000000000004");
    private static final UUID INSTRUMENT = UUID.fromString("c2000000-0000-4000-8000-000000000005");
    private static final UUID FEE_POLICY = UUID.fromString("c2000000-0000-4000-8000-000000000006");
    private static final UUID BUFFER_POLICY = UUID.fromString("c2000000-0000-4000-8000-000000000007");
    private static final UUID ACCOUNT = UUID.fromString("c2000000-0000-4000-8000-000000000008");

    /** The flow key the plan names; B writes it as {@code bot.flows.name}. */
    private static final String FLOW_KEY = "flow-1";

    private static final Instant ELIGIBLE_FROM = Instant.parse("2026-08-04T13:30:00Z");
    private static final Instant EVENT_AT = Instant.parse("2026-08-04T14:30:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        seed(dataSource);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private EvaluatingBotRuntime runtime;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CandidateBatchProcessor processor;

    @Autowired
    private OrderCandidateBatchAdapter adapter;

    @Autowired
    private EvaluatingBotRuntime.BotScopeResolver scopeResolver;

    @Autowired
    private EvaluatingBotRuntime.EvaluationRunRecorder runRecorder;

    /**
     * Each case starts from an unregistered bot and an empty canonical record.
     *
     * <p>The runtime is a singleton bean and the container is shared across the class, so without this
     * a case asserting "nothing was written" would be reading the previous case's order. Truncating
     * cascades through the order, reservation and event rows that hang off a batch.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetRuntimeAndCanonicalRecord() {
        runtime.stop(BOT, "TEST_RESET");
        jdbc.sql("""
                truncate trading.order_intent_batches, trading.order_intents, trading.orders,
                         trading.order_components, trading.resource_reservations,
                         trading.candidate_batch_processing
                cascade
                """).update();
        jdbc.sql("delete from bot.evaluation_runs where bot_id = :bot").param("bot", BOT).update();
        jdbc.sql("delete from bot.bot_events where bot_id = :bot").param("bot", BOT).update();
    }

    @Test
    void aStartedBotTurnsAMarketEventIntoACanonicalOrder() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        assertTrue(runtime.isEvaluating(BOT));

        List<CandidateBatchProcessingResult> results = runtime.feed(event(1, "84"));

        assertAll(
                () -> assertEquals(1, results.size()),
                () -> assertEquals(CandidateBatchProcessingResult.PROCESSED, results.getFirst()),
                // The evaluation recorded its own judgment, which the intent's foreign key requires.
                () -> assertEquals(1, count("select count(*) from bot.evaluation_runs where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from bot.bot_events where bot_id = ? "
                                + "and event_type = 'EVALUATION_COMPLETED'", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intent_batches where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                // Sized by the composer from the share, so the intent carries a real quantity.
                () -> assertTrue(new BigDecimal(text(
                        "select final_quantity::text from trading.order_intents where bot_id = ?", BOT))
                        .signum() > 0),
                () -> assertEquals("APPROVED", text(
                        "select decision::text from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_state_projections "
                                + "where bot_id = ? and status = 'OPEN'", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations where bot_id = ?", BOT)));
    }

    @Test
    void aDirectPriceBlockTradesFromCompletedMarketBarsWithoutRsi() {
        runtime.start(directPricePlan(), PreparedWarmup.none(),
                EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));

        List<CandidateBatchProcessingResult> first = runtime.feed(event(1, "100"));
        List<CandidateBatchProcessingResult> crossed = runtime.feed(event(2, "101"));

        assertAll(
                () -> assertTrue(first.isEmpty(), "the previous close is not available yet"),
                () -> assertEquals(List.of(CandidateBatchProcessingResult.PROCESSED), crossed),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    /**
     * A repeat or a late arrival leaves canonical exactly as it was.
     *
     * <p>The bot declines to evaluate an event that does not move its market position forward — a
     * stale decision would look current — so the second feed does no work at all. The claim ledger
     * remains the durable guarantee behind that, which is what the F90 proofs cover; here the point is
     * that one market event yields one intent and one reservation however many times it arrives.
     */
    @Test
    void aRedeliveredOrLateMarketEventChangesNothing() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));

        MarketEventEnvelope event = event(20, "84");
        List<CandidateBatchProcessingResult> first = runtime.feed(event);
        List<CandidateBatchProcessingResult> repeat = runtime.feed(event);
        List<CandidateBatchProcessingResult> late = runtime.feed(event(19, "83"));

        assertAll(
                () -> assertEquals(CandidateBatchProcessingResult.PROCESSED, first.getFirst()),
                () -> assertTrue(repeat.isEmpty(), "a repeat is not evaluated again"),
                () -> assertTrue(late.isEmpty(), "an event behind the bot's position is not evaluated"),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations where bot_id = ?", BOT)),
                () -> assertEquals(1, count("select count(*) from bot.evaluation_runs where bot_id = ?", BOT)));
    }

    /**
     * A process restart loses the in-memory market sequence, but it must not lose the event's durable
     * identity. The replacement runtime deliberately starts with fresh feature and sequence state,
     * then receives the exact event the first process already committed. Evaluation, candidate-batch,
     * intent and reservation identities must converge on the existing canonical rows.
     */
    @Test
    void aMarketEventRedeliveredAfterProcessRestartHasOneCanonicalEffect() {
        MarketEventEnvelope deliveredBeforeRestart = event(21, "84");
        EvaluatingBotRuntime firstProcess = freshRuntime();
        firstProcess.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));

        List<CandidateBatchProcessingResult> first = firstProcess.feed(deliveredBeforeRestart);
        String evaluationId = text(
                "select id::text from bot.evaluation_runs where bot_id = ?", BOT);
        String batchId = text(
                "select id::text from trading.order_intent_batches where bot_id = ?", BOT);
        String reservationId = text(
                "select id::text from trading.resource_reservations where bot_id = ?", BOT);
        String reservedAmount = text(
                "select reserved_amount::text from trading.resource_reservations where bot_id = ?", BOT);
        long ledgerTransactions = count(
                "select count(*) from trading.ledger_transactions where bot_id = ?", BOT);

        EvaluatingBotRuntime replacementProcess = freshRuntime();
        replacementProcess.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        List<CandidateBatchProcessingResult> redelivered =
                replacementProcess.feed(deliveredBeforeRestart);

        assertAll(
                () -> assertEquals(List.of(CandidateBatchProcessingResult.PROCESSED), first),
                () -> assertEquals(List.of(CandidateBatchProcessingResult.DUPLICATE), redelivered),
                () -> assertEquals(1, count(
                        "select count(*) from bot.evaluation_runs where bot_id = ?", BOT)),
                () -> assertEquals(evaluationId, text(
                        "select id::text from bot.evaluation_runs where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from bot.bot_events where bot_id = ? "
                                + "and event_type = 'EVALUATION_COMPLETED'", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intent_batches where bot_id = ?", BOT)),
                () -> assertEquals(batchId, text(
                        "select id::text from trading.order_intent_batches where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.orders where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.resource_reservations where bot_id = ?", BOT)),
                () -> assertEquals(reservationId, text(
                        "select id::text from trading.resource_reservations where bot_id = ?", BOT)),
                () -> assertEquals(reservedAmount, text(
                        "select reserved_amount::text from trading.resource_reservations where bot_id = ?", BOT)),
                () -> assertEquals(ledgerTransactions, count(
                        "select count(*) from trading.ledger_transactions where bot_id = ?", BOT),
                        "redelivery after restart must not mutate the official ledger"));
    }

    /** An event before the bot's eligibility instant is not its concern: a room bot waits. */
    @Test
    void anEventBeforeEligibilityIsNotEvaluated() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(EVENT_AT.plusSeconds(3600)));

        assertTrue(runtime.feed(event(3, "84")).isEmpty());
        assertEquals(0, count("select count(*) from trading.order_intents where bot_id = ?", BOT));
    }

    /**
     * C93: a room bot stops deciding when its room's evaluation window closes, whether or not the stop
     * command has arrived yet.
     *
     * <p>This is the boundary that matters, because everything past it is indistinguishable in the
     * canonical record from a trade that belongs to the room. Enforcing it here rather than waiting for
     * B's scheduler means a late stop delays the settlement, not the boundary.
     *
     * <p>The end is exclusive: an event stamped exactly at it is the first one outside the window.
     */
    @Test
    void aRoomBotDoesNotEvaluateAtOrAfterItsEvaluationWindowEnds() {
        runtime.start(plan(), warmup(), new EvaluationWindow(ELIGIBLE_FROM, EVENT_AT));

        List<CandidateBatchProcessingResult> atTheEnd = runtime.feed(event(6, "84"));
        List<CandidateBatchProcessingResult> afterTheEnd = runtime.feed(
                eventAt(7, "83", EVENT_AT.plusSeconds(60)));

        assertAll(
                () -> assertTrue(atTheEnd.isEmpty(), "the closing instant is already outside"),
                () -> assertTrue(afterTheEnd.isEmpty()),
                () -> assertTrue(runtime.isEvaluating(BOT),
                        "still registered: the window closing is not the stop, which settles separately"),
                () -> assertEquals(0, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT),
                        "nothing past the room's end reached the ledger its performance is read from"),
                () -> assertEquals(0, count(
                        "select count(*) from bot.evaluation_runs where bot_id = ?", BOT),
                        "and no judgment was recorded either"));
    }

    /** The same bot, one bar earlier, does decide — so the case above is a boundary, not a dead plan. */
    @Test
    void aRoomBotStillEvaluatesInsideItsEvaluationWindow() {
        runtime.start(plan(), warmup(), new EvaluationWindow(ELIGIBLE_FROM, EVENT_AT.plusSeconds(60)));

        List<CandidateBatchProcessingResult> inside = runtime.feed(event(8, "84"));

        assertAll(
                () -> assertEquals(1, inside.size()),
                () -> assertEquals(CandidateBatchProcessingResult.PROCESSED, inside.getFirst()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    /** A stopped bot evaluates nothing, whatever the market does. */
    @Test
    void aStoppedBotEvaluatesNothing() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        runtime.stop(BOT, "USER_REQUESTED");

        assertFalse(runtime.isEvaluating(BOT));
        assertTrue(runtime.feed(event(4, "84")).isEmpty());
        assertEquals(0, count("select count(*) from trading.order_intents where bot_id = ?", BOT));
    }

    /**
     * Without the warm-up the same event decides nothing: RSI_14 holds no value until it has fifteen
     * completed bars, so the plan's comparison never gets an operand.
     */
    @Test
    void withoutAWarmedWindowTheSameEventProducesNoCandidate() {
        runtime.start(plan(), null, EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));

        assertTrue(runtime.feed(event(5, "84")).isEmpty());
        assertEquals(0, count("select count(*) from trading.order_intents where bot_id = ?", BOT));
    }

    /** An instrument the plan does not subscribe to is ignored rather than mis-evaluated. */
    @Test
    void anUnsubscribedInstrumentIsIgnored() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));

        MarketEventEnvelope other = new MarketEventEnvelope(
                "market-other", 2, UUID.fromString("c2000000-0000-4000-8000-0000000000ff"),
                "ALPACA", "SIP", MarketEventType.MARKET_EVALUATION_READY, "p-other", EVENT_AT, EVENT_AT,
                99, 0, null, evaluationValues("84"));

        assertTrue(runtime.feed(other).isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private LoadedExecutionPlan plan() {
        return new LoadedExecutionPlan(
                BOT, RELEASE, "basic-compiled-plan.v1", Set.of(), planDocument(),
                "strategy-bot-runtime.v1", 0, Map.of());
    }

    private LoadedExecutionPlan directPricePlan() {
        return new LoadedExecutionPlan(
                BOT, RELEASE, "basic-compiled-plan.v1", Set.of(), directPricePlanDocument(),
                "strategy-bot-runtime.v1", 0, Map.of());
    }

    private EvaluatingBotRuntime freshRuntime() {
        return new EvaluatingBotRuntime(processor, adapter, scopeResolver, runRecorder);
    }

    /**
     * Fourteen falling closes, which is a window RSI_14 reads as deeply oversold — below the plan's
     * threshold of 30 — so the live event's continued fall keeps the condition true.
     */
    private PreparedWarmup warmup() {
        List<FeatureObservation> observations = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            observations.add(new FeatureObservation(
                    INSTRUMENT.toString(),
                    ELIGIBLE_FROM.minusSeconds(60L * (14 - index)),
                    new BigDecimal(100 - index)));
        }
        return new PreparedWarmup(
                "manifest-rt2", "dataset-rt2", 1, "a".repeat(64),
                Map.of("rsi-14-pt1m", new WarmupFeatureSeries(
                        "rsi-14-pt1m", "RSI_14", "1.0.0", "PT1M", "manifest-rt2", "a".repeat(64),
                        observations)));
    }

    private MarketEventEnvelope event(long sequence, String close) {
        return eventAt(sequence, close, EVENT_AT);
    }

    private MarketEventEnvelope eventAt(long sequence, String close, Instant observedAt) {
        return new MarketEventEnvelope(
                "market-" + sequence, 2, INSTRUMENT, "ALPACA", "SIP", MarketEventType.MARKET_EVALUATION_READY,
                "provider-" + sequence, observedAt, observedAt, sequence, 0, null,
                evaluationValues(close));
    }

    private static Map<String, BigDecimal> evaluationValues(String close) {
        BigDecimal price = new BigDecimal(close);
        return Map.ofEntries(
                Map.entry("close", price),
                Map.entry("closed30m", BigDecimal.ONE),
                Map.entry("closed1h", BigDecimal.ZERO),
                Map.entry("closed4h", BigDecimal.ZERO),
                Map.entry("closed1d", BigDecimal.ZERO),
                Map.entry("open30m", price),
                Map.entry("high30m", price),
                Map.entry("low30m", price),
                Map.entry("close30m", price),
                Map.entry("volume30m", BigDecimal.ONE));
    }

    /** The document B publishes, buying when RSI_14 falls below 30 with equal allocation. */
    private static String planDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",
                "elementCatalogVersion":"basic-elements:2026-08-04",
                "instrumentCatalogVersion":"us-supported-universe:2026-08-04",
                "compilerVersion":"basic-compiler:1.0.0",
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt1m",
                "featureId":"c2000000-0000-4000-8000-000000000401","featureVersion":"1.0.0",
                "instruments":["%s"],"resolution":"PT1M","requiredObservations":14}],
                "executionSnapshot":{"immutableStrategyVersion":{
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,
                "flows":[{"key":"%s","officialInstrumentIds":["%s"]}]}]},
                "steps":[{"sequence":1,"operation":"LOAD_FEATURE",
                "arguments":{"feature":"RSI_14","resolution":"1m"}},{"sequence":2,"operation":"COMPARE",
                "arguments":{"operator":"LT","threshold":"30"}},{"sequence":3,
                "operation":"EMIT_ORDER_CANDIDATE",
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],
                "planChecksum":"sha256:%s"}
                """.formatted("3".repeat(64), INSTRUMENT, "2".repeat(64), "1".repeat(64),
                        FLOW_KEY, INSTRUMENT, "4".repeat(64));
    }

    private static String directPricePlanDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",
                "elementCatalogVersion":"basic-elements:2026-08-07",
                "instrumentCatalogVersion":"us-supported-universe:2026-08-07",
                "compilerVersion":"basic-compiler:1.0.0",
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[],
                "executionSnapshot":{"immutableStrategyVersion":{
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,
                "flows":[{"key":"%s","officialInstrumentIds":["%s"]}]}]},
                "steps":[{"sequence":1,"operation":"PRICE_COMPARE",
                "arguments":{"resolution":"1m","operator":"GT","reference":"PREVIOUS_CLOSE"}},
                {"sequence":2,"operation":"EMIT_ORDER_CANDIDATE",
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],
                "planChecksum":"sha256:%s"}
                """.formatted("3".repeat(64), "2".repeat(64), "1".repeat(64),
                        FLOW_KEY, INSTRUMENT, "4".repeat(64));
    }

    private static void seed(DriverManagerDataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('%s', 'ACTIVE', '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(ACCOUNT));
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', '%s', 'BASIC', 'RT2 bot', 'RUNNING', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(BOT, ACCOUNT));
            statement.addBatch("""
                    insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                        calculation_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'rt2', 20, 'v1', 'sha256:rt2-fee',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(FEE_POLICY));
            statement.addBatch("""
                    insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                        buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'rt2', 100, 'v1', 'sha256:rt2-buffer',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(BUFFER_POLICY));
            statement.addBatch("""
                    insert into bot.launch_configurations (bot_id, initial_cash_amount, currency_code,
                        broker_rules_version, accounting_rules_version, precision_rules_version,
                        fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id,
                        candidate_conflict_policy, configuration_hash)
                    values ('%s', 100000, 'USD', 'broker-rules:v1', 'accounting-rules:v1',
                        'precision-rules:v1', '%s', 5, '%s', '{}', '%s')
                    """.formatted(BOT, FEE_POLICY, BUFFER_POLICY, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps, position_x,
                        position_y, configuration_hash)
                    values ('%s', '%s', 'RT2 strategy', 10000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
            // The flow's name is the plan's flow key, which is how the scope resolver finds it.
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', '%s', gen_random_uuid(), gen_random_uuid(), 0, 0, '{}', '{}',
                        'v1', '%s', '%s', '%s')
                    """.formatted(FLOW, PARTITION, FLOW_KEY,
                            "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(INSTRUMENT));
            statement.addBatch("""
                    insert into trading.bot_budget_projections (bot_id, currency_code,
                        available_cash_amount, active_reservation_amount, invested_amount,
                        segregated_short_proceeds_amount, short_collateral_amount, valuation_at,
                        valuation_status, last_event_sequence, projection_hash, updated_at)
                    values ('%s', 'USD', 100000, 0, 0, 0, 0, '2026-08-04T13:00:00+00', 'VALUED', 1,
                        'rt2-bot', '2026-08-04T13:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into trading.partition_budget_projections (partition_id, bot_id,
                        currency_code, budget_cap_amount, active_reservation_amount, invested_amount,
                        segregated_short_proceeds_amount, short_collateral_amount, valuation_at,
                        valuation_status, last_event_sequence, projection_hash, updated_at)
                    values ('%s', '%s', 'USD', 100000, 0, 0, 0, 0, '2026-08-04T13:00:00+00', 'VALUED',
                        1, 'rt2-partition', '2026-08-04T13:00:00+00')
                    """.formatted(PARTITION, BOT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed the evaluation loop's parents", failure);
        }
    }

    private long count(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(Long.class).single();
    }

    private String text(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(String.class).single();
    }
}
