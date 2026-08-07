package com.idea2strategy.trading.worker.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.strategy.runtime.control.BotStartupGate;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * B91: B's start and stop commands reach real evaluation and real settlement, and stay consistent
 * when they arrive twice or out of order.
 *
 * <p>Nothing here is hand-delivered. A command is a row B's backend wrote to
 * {@code operations.outbox_messages}; the real poller claims it; the wired production consumer decodes
 * it with the real codec, reads the compiled plan B published in {@code bot.launch_contract_plans} —
 * recomputing the plan checksum rather than trusting it — and hands the bot to the F91 bridge wrapping
 * the RT2 evaluation loop. Each case therefore asserts what a user or an operator would see: whether
 * the bot evaluates, and which canonical rows exist.
 *
 * <p>Redelivery and reordering are the point. B's transport is at-least-once and its outbox promises no
 * ordering between messages, so all of these arrivals are ordinary in production: a message polled
 * twice, a message redelivered because the worker died before committing its receipt, and a run command
 * published before a stop that reaches this worker after it. The last has teeth — a stop is absorbing,
 * because a bot that resumed trading after its owner stopped it is the worst outcome this system could
 * produce.
 *
 * <p>The warm-up gate is the single substitution. A real warm-up needs D's dataset manifests and
 * objects, which C91 and D90 already prove end to end; here it hands over a prepared window so the
 * subject stays the control path. The window is fourteen falling closes, which RSI_14 reads as deeply
 * oversold, so a started bot genuinely buys — an assertion that would pass either way if the plan
 * decided nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true",
        // The scheduled cycle would race the poller each case drives on its own clock.
        "trading.bot-control.transport.enabled=false"
})
class BotControlIntegrationE2ETest {
    private static final Path WARMUP_ROOT = prepareWarmupMaterialization();

    private static final UUID BOT = UUID.fromString("b9100000-0000-4000-8000-000000000001");
    private static final UUID PARTITION = UUID.fromString("b9100000-0000-4000-8000-000000000003");
    private static final UUID FLOW = UUID.fromString("b9100000-0000-4000-8000-000000000004");
    private static final UUID INSTRUMENT = UUID.fromString("b9100000-0000-4000-8000-000000000005");
    private static final UUID FEE_POLICY = UUID.fromString("b9100000-0000-4000-8000-000000000006");
    private static final UUID BUFFER_POLICY = UUID.fromString("b9100000-0000-4000-8000-000000000007");
    private static final UUID ACCOUNT = UUID.fromString("b9100000-0000-4000-8000-000000000008");

    /** The flow key the plan names; B writes it as {@code bot.flows.name}. */
    private static final String FLOW_KEY = "flow-1";

    private static final String SEMANTIC_HASH = "2".repeat(64);
    private static final String SNAPSHOT_HASH = "1".repeat(64);

    /**
     * The checksum of {@link #planDocument()} under the contract's own material.
     *
     * <p>Pinned rather than derived here on purpose: the codec recomputes it from the fields it decoded
     * and refuses a mismatch, so a fixture computing it the same way would verify nothing. Editing the
     * plan below without updating this fails loudly, which is what a checksummed contract is for.
     */
    private static final String PLAN_CHECKSUM =
            "sha256:1edb5912c55919d0a3812319d9d9e316dce6c73f1b17b81c0aa700d53a1481db";

    private static final Instant ELIGIBLE_FROM = Instant.parse("2026-08-02T13:30:00Z");
    private static final Instant EVENT_AT = Instant.parse("2026-08-02T14:30:00Z");
    private static final Instant POLL_AT = Instant.parse("2026-08-02T13:29:30Z");

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
        registry.add("trading.warmup.bundle-root", WARMUP_ROOT::toString);
        registry.add(
                "trading.warmup.materialization-receipt-path",
                () -> WARMUP_ROOT.resolve("receipt.properties").toString());
    }

    private static Path prepareWarmupMaterialization() {
        try {
            Path root = Path.of("build/tmp/b91-warmup").toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path manifest = Files.writeString(root.resolve("manifest.json"), "{}");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(manifest)));
            Files.writeString(root.resolve("receipt.properties"), """
                    contract=i2s.materialization-receipt
                    schema-version=1
                    artifact-count=1
                    artifact.0.id=warmup-manifest
                    artifact.0.source-bucket=runtime-bucket
                    artifact.0.source-key=trading/warmup/manifest.json
                    artifact.0.source-version-id=b91-v1
                    artifact.0.sha256=%s
                    artifact.0.local-path=%s
                    """.formatted(hash, manifest.toString().replace('\\', '/')));
            return root;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** Hands over a prepared window in place of D's bundle; see the class comment. */
    @TestConfiguration
    static class PreparedWarmupConfiguration {
        @Bean
        @Primary
        BotStartupGate preparedWarmupGate() {
            return (request, starter) -> {
                PreparedWarmup prepared = warmup();
                starter.accept(prepared);
                return prepared;
            };
        }
    }

    @Autowired
    private StrategyBotControlConsumer consumer;

    @Autowired
    private EvaluatingBotRuntime runtime;

    @Autowired
    private JdbcClient jdbc;

    /** The clock the poller leases with, so a case can let a lease lapse without waiting for one. */
    private final AdvanceableClock clock = new AdvanceableClock(POLL_AT);

    private StrategyBotOutboxPoller poller;

    /**
     * Each case starts from a bot that has never been controlled and an empty canonical record.
     *
     * <p>The consumer, its receipts and the runtime all outlive a single case over a shared container,
     * so without this a case asserting "no order exists" would read the previous case's order and a
     * redelivery case would find a receipt it never wrote.
     */
    @BeforeEach
    void resetControlStateAndCanonicalRecord() {
        runtime.stop(BOT, "TEST_RESET");
        clock.set(POLL_AT);
        poller = new StrategyBotOutboxPoller(
                jdbc, consumer, clock, StrategyBotControlTransportConfiguration.HANDLER_ID,
                "b91-worker", 32, Duration.ofSeconds(30), Duration.ofSeconds(30), 5);
        jdbc.sql("""
                truncate trading.order_intent_batches, trading.order_intents, trading.orders,
                         trading.order_components, trading.resource_reservations,
                         trading.candidate_batch_processing
                cascade
                """).update();
        jdbc.sql("delete from operations.outbox_consumer_receipts").update();
        jdbc.sql("delete from operations.outbox_messages where owner_domain = 'strategy-bot'").update();
        jdbc.sql("delete from bot.evaluation_runs where bot_id = :bot").param("bot", BOT).update();
        jdbc.sql("delete from bot.bot_events where bot_id = :bot").param("bot", BOT).update();
    }

    /** A published start command, polled once, makes the bot evaluate for real. */
    @Test
    void aPublishedRunCommandStartsTheBotAndItsNextMarketEventBecomesACanonicalOrder() {
        publishRun(1, "a1");

        int delivered = poller.pollOnce();
        List<?> processed = runtime.feed(event(1, "84"));

        assertAll(
                () -> assertEquals(1, delivered),
                () -> assertTrue(runtime.isEvaluating(BOT), "the loop registered the bot"),
                () -> assertTrue(consumer.canAcceptEvaluation(BOT), "and the checkpoint says RUNNING"),
                () -> assertEquals(1, processed.size()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from trading.orders o join trading.order_components c "
                                + "on c.order_id = o.id join trading.order_intents i on i.id = c.intent_id "
                                + "where i.bot_id = ?", BOT)),
                () -> assertEquals("COMPLETED", receiptStatus()));
    }

    /**
     * A second polling cycle over the same command. The completed receipt is what makes B's
     * at-least-once transport idempotent, so the message is never claimed a second time.
     */
    @Test
    void aSecondPollingCycleDoesNotDeliverACompletedCommandAgain() {
        publishRun(1, "a1");
        assertEquals(1, poller.pollOnce());
        runtime.feed(event(1, "84"));

        int delivered = poller.pollOnce();

        assertAll(
                () -> assertEquals(0, delivered, "a completed receipt is not reclaimed"),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(1, count(
                        "select count(*) from bot.evaluation_runs where bot_id = ?", BOT)));
    }

    /**
     * The redelivery a receipt cannot prevent: the worker consumed the command and died before
     * committing the receipt, so the lease lapses and the next cycle reclaims it.
     *
     * <p>What makes this safe is that starting an already-registered bot writes nothing: the checkpoint
     * is derived from <em>completed</em> receipts, so it holds no memory of a delivery that never
     * completed and the consumer treats the redelivery as a fresh start. Re-registering is all that
     * start does, so the bot that was already trading keeps trading and the canonical record is
     * unchanged.
     *
     * <p>Re-registration resets the bot's in-memory market sequence, so a replayed market event reaches
     * evaluation again. Its evaluation and candidate-batch identities are derived from the stable
     * market-event identity, however, so the durable claim boundary converges on the existing rows.
     * {@code EvaluationLoopE2ETest} proves that replacement-process boundary explicitly.
     */
    @Test
    void aRunCommandRedeliveredAfterALapsedLeaseWritesNothingNew() {
        publishRun(1, "a1");
        assertEquals(1, poller.pollOnce());
        runtime.feed(event(1, "84"));
        abandonTheReceiptAsACrashWould();

        clock.advance(Duration.ofMinutes(5));
        int redelivered = poller.pollOnce();

        assertAll(
                () -> assertEquals(1, redelivered, "the lapsed lease is reclaimed"),
                () -> assertTrue(runtime.isEvaluating(BOT), "and the bot is still evaluating"),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT),
                        "the redelivered command itself changed nothing"),
                () -> assertEquals(1, count(
                        "select count(*) from bot.evaluation_runs where bot_id = ?", BOT)));
    }

    /** A start command from behind the sequence already processed is a stale arrival, not a restart. */
    @Test
    void aRunCommandBehindTheProcessedSequenceIsIgnored() {
        publishRun(4, "a4");
        assertEquals(1, poller.pollOnce());

        publishRun(2, "a2");
        int delivered = poller.pollOnce();

        assertAll(
                () -> assertEquals(1, delivered, "it was delivered, then judged stale"),
                () -> assertTrue(runtime.isEvaluating(BOT), "the running bot is left running"),
                () -> assertTrue(consumer.canAcceptEvaluation(BOT)));
    }

    /**
     * A stop settles for real: the bot stops evaluating, the settlement is recorded canonically, and a
     * market event delivered afterwards decides nothing.
     */
    @Test
    void aStopCommandEndsEvaluationAndSettlesDurably() {
        publishRun(1, "b1");
        assertEquals(1, poller.pollOnce());
        runtime.feed(event(1, "84"));
        long ordersBefore = count("select count(*) from trading.order_intents where bot_id = ?", BOT);

        publishStop(2, "b2");
        int delivered = poller.pollOnce();
        List<?> afterStop = runtime.feed(event(2, "83"));

        assertAll(
                () -> assertEquals(1, delivered),
                () -> assertEquals(1, ordersBefore, "the bot had traded before the stop"),
                () -> assertFalse(runtime.isEvaluating(BOT), "the loop unregistered the bot"),
                () -> assertFalse(consumer.canAcceptEvaluation(BOT),
                        "and the checkpoint refuses further evaluation"),
                () -> assertTrue(afterStop.isEmpty(), "an event after the stop decides nothing"),
                () -> assertEquals(1, settlementsCompleted(),
                        "the stop settled durably, not only in this process"),
                () -> assertEquals(ordersBefore, count(
                        "select count(*) from trading.order_intents where bot_id = ? "
                                + "and cast(origin_type as varchar) = 'FLOW_EVALUATION'", BOT)));
    }

    /**
     * The reordering that matters: a run command published before the stop but reaching this worker
     * after it. A stop is absorbing, so the bot stays stopped.
     */
    @Test
    void aRunCommandArrivingAfterTheStopDoesNotReviveTheBot() {
        publishRun(1, "c1");
        assertEquals(1, poller.pollOnce());
        runtime.feed(event(1, "84"));
        publishStop(3, "c3");
        assertEquals(1, poller.pollOnce());

        // Published earlier in B's sequence, delivered now: the outbox promises no order between messages.
        publishRun(2, "c2");
        int delivered = poller.pollOnce();
        List<?> afterLateRun = runtime.feed(event(2, "83"));

        assertAll(
                () -> assertEquals(1, delivered),
                () -> assertFalse(runtime.isEvaluating(BOT), "the late run did not restart the loop"),
                () -> assertFalse(consumer.canAcceptEvaluation(BOT)),
                () -> assertTrue(afterLateRun.isEmpty()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ? "
                                + "and cast(origin_type as varchar) = 'FLOW_EVALUATION'", BOT)));
    }

    /**
     * A second distinct stop after one already settled. It must not settle again: liquidating twice
     * would sell a position the bot no longer holds.
     */
    @Test
    void aSecondStopCommandSettlesOnlyOnce() {
        publishRun(1, "d1");
        assertEquals(1, poller.pollOnce());
        publishStop(2, "d2");
        assertEquals(1, poller.pollOnce());

        publishStop(3, "d3");
        int delivered = poller.pollOnce();

        assertAll(
                () -> assertEquals(1, delivered),
                () -> assertEquals(1, settlementsCompleted(), "a stop after a stop is absorbed"));
    }

    /**
     * A command naming another release. The bot's published plan pins the snapshot it was released
     * with, so a command expecting a different one describes a strategy this bot is not running: it
     * fails and stays retryable rather than starting the wrong thing.
     */
    @Test
    void aCommandNamingAnotherReleaseIsRefusedAndLeftForRetry() {
        publish("BOT_RUN_COMMAND", 1, "e1",
                "\"executionEligibleFrom\":\"" + ELIGIBLE_FROM + "\"", "9".repeat(64));

        int delivered = poller.pollOnce();

        assertAll(
                () -> assertEquals(0, delivered),
                () -> assertEquals("RETRYABLE_FAILURE", receiptStatus()),
                () -> assertFalse(runtime.isEvaluating(BOT)),
                () -> assertEquals(0, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    // ------------------------------------------------------------------ fixtures

    /** Leaves the receipt claimed with a lapsed lease, which is what a crashed worker leaves behind. */
    private void abandonTheReceiptAsACrashWould() {
        jdbc.sql("""
                update operations.outbox_consumer_receipts set
                    status = 'PROCESSING', claim_token = gen_random_uuid(), claimed_by = 'crashed',
                    claimed_at = :at, claim_expires_at = :expiresAt, completed_at = null
                where consumer_handler_id = :handlerId
                """)
                .param("at", POLL_AT.atOffset(ZoneOffset.UTC))
                .param("expiresAt", POLL_AT.plusSeconds(30).atOffset(ZoneOffset.UTC))
                .param("handlerId", StrategyBotControlTransportConfiguration.HANDLER_ID)
                .update();
    }

    private void publishRun(long sequence, String keySuffix) {
        publish("BOT_RUN_COMMAND", sequence, keySuffix,
                "\"executionEligibleFrom\":\"" + ELIGIBLE_FROM + "\"", SNAPSHOT_HASH);
    }

    private void publishStop(long sequence, String keySuffix) {
        publish("BOT_STOP_COMMAND", sequence, keySuffix,
                "\"reasonCode\":\"USER_REQUESTED\"", SNAPSHOT_HASH);
    }

    /** One row exactly as B's backend writes it; the codec, not the test, decides if it is valid. */
    private void publish(
            String messageType, long sequence, String keySuffix, String typedField, String snapshotHash) {
        String key = "sha256:" + keySuffix.repeat(64 / keySuffix.length());
        UUID messageId = UUID.nameUUIDFromBytes(
                (messageType + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = ("{\"metadata\":{\"contractVersion\":\"strategy-bot.v1\","
                + "\"messageType\":\"%s\",\"messageId\":\"%s\","
                + "\"occurredAt\":\"2026-08-02T13:29:00Z\","
                + "\"correlationId\":\"b91e0000-0000-4000-8000-000000000001\","
                + "\"idempotencyKey\":\"%s\"},"
                + "\"botId\":\"%s\",\"expectedSnapshotHash\":\"sha256:%s\",%s}")
                .formatted(messageType, messageId, key, BOT, snapshotHash, typedField);
        jdbc.sql("""
                insert into operations.outbox_messages (
                    id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                    event_schema_version, payload_document, idempotency_key, created_at)
                values (:id, 'strategy-bot', :botId, :sequence, :eventType, 'strategy-bot.v1',
                    cast(:payload as jsonb), :key, :createdAt)
                """)
                .param("id", messageId)
                .param("botId", BOT)
                .param("sequence", sequence)
                .param("eventType", messageType)
                .param("payload", payload)
                .param("key", key)
                .param("createdAt", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private String receiptStatus() {
        return jdbc.sql("""
                select cast(status as varchar) from operations.outbox_consumer_receipts
                where consumer_handler_id = :handlerId
                """)
                .param("handlerId", StrategyBotControlTransportConfiguration.HANDLER_ID)
                .query(String.class)
                .single();
    }

    private long settlementsCompleted() {
        return count(
                "select count(*) from bot.bot_events where bot_id = ? "
                        + "and event_type = 'SETTLEMENT_COMPLETED'", BOT);
    }

    /**
     * Fourteen falling closes, which RSI_14 reads as deeply oversold — below the plan's threshold of
     * 30 — so the fed event's continued fall keeps the buy condition true.
     */
    private static PreparedWarmup warmup() {
        List<FeatureObservation> observations = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            observations.add(new FeatureObservation(
                    INSTRUMENT.toString(),
                    ELIGIBLE_FROM.minusSeconds(60L * (14 - index)),
                    new BigDecimal(100 - index)));
        }
        return new PreparedWarmup(
                "manifest-b91", "dataset-b91", 1, "a".repeat(64),
                Map.of("rsi-14-pt30m", new WarmupFeatureSeries(
                        "rsi-14-pt30m", "RSI_14", "1.0.0", "PT30M", "manifest-b91", "a".repeat(64),
                        observations)));
    }

    private MarketEventEnvelope event(long sequence, String close) {
        return new MarketEventEnvelope(
                "market-" + sequence, 1, INSTRUMENT, "ALPACA", "SIP", MarketEventType.MARKET_EVALUATION_READY,
                "provider-" + sequence, EVENT_AT, EVENT_AT, sequence, 0, null,
                Map.of("close", new BigDecimal(close)));
    }

    /** The document B publishes for this bot, buying when RSI_14 falls below 30. */
    private static String planDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",\
                "elementCatalogVersion":"basic-elements:2026-08-04",\
                "instrumentCatalogVersion":"us-supported-universe:2026-08-04",\
                "compilerVersion":"basic-compiler:1.0.0",\
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt30m",\
                "featureId":"b9100000-0000-4000-8000-000000000401","featureVersion":"1.0.0",\
                "instruments":["%s"],"resolution":"PT30M","requiredObservations":14}],\
                "executionSnapshot":{"immutableStrategyVersion":{\
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",\
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",\
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,\
                "flows":[{"key":"%s","officialInstrumentIds":["%s"]}]}]},\
                "steps":[{"sequence":1,"operation":"LOAD_FEATURE",\
                "arguments":{"feature":"RSI_14","resolution":"30m"}},{"sequence":2,"operation":"COMPARE",\
                "arguments":{"operator":"LT","threshold":"30"}},{"sequence":3,\
                "operation":"EMIT_ORDER_CANDIDATE",\
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],\
                "planChecksum":"%s"}"""
                .formatted("3".repeat(64), INSTRUMENT, SEMANTIC_HASH, SNAPSHOT_HASH,
                        FLOW_KEY, INSTRUMENT, PLAN_CHECKSUM);
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
                    values ('%s', '%s', 'BASIC', 'B91 bot', 'RUNNING', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(BOT, ACCOUNT));
            statement.addBatch("""
                    insert into bot.launch_snapshots (bot_id, snapshot_schema_version,
                        semantic_snapshot, presentation_snapshot, semantic_hash, presentation_hash,
                        snapshot_hash)
                    values ('%s', 'basic-launch-snapshot.v1', '{}', '{}', '%s', '%s', '%s')
                    """.formatted(BOT, SEMANTIC_HASH, "5".repeat(64), SNAPSHOT_HASH));
            // What B's release writes, and the only thing this runtime loads a bot from.
            statement.addBatch("""
                    insert into bot.launch_contract_plans (bot_id, contract_version,
                        plan_schema_version, plan_checksum, plan_document)
                    values ('%s', 'strategy-bot.v1', 'basic-compiled-plan.v1', '%s', '%s'::jsonb)
                    """.formatted(BOT, PLAN_CHECKSUM, planDocument().replace("'", "''")));
            statement.addBatch("""
                    insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                        calculation_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'b91', 20, 'v1', 'sha256:b91-fee',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(FEE_POLICY));
            statement.addBatch("""
                    insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                        buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'b91', 100, 'v1', 'sha256:b91-buffer',
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
                    values ('%s', '%s', 'B91 strategy', 10000, 0, 0, '%s')
                    """.formatted(PARTITION, BOT, "c".repeat(64)));
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
                        'b91-bot', '2026-08-04T13:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into trading.partition_budget_projections (partition_id, bot_id,
                        currency_code, budget_cap_amount, active_reservation_amount, invested_amount,
                        segregated_short_proceeds_amount, short_collateral_amount, valuation_at,
                        valuation_status, last_event_sequence, projection_hash, updated_at)
                    values ('%s', '%s', 'USD', 100000, 0, 0, 0, 0, '2026-08-04T13:00:00+00', 'VALUED',
                        1, 'b91-partition', '2026-08-04T13:00:00+00')
                    """.formatted(PARTITION, BOT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed the bot control integration parents", failure);
        }
    }

    private long count(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(Long.class).single();
    }

    /** A clock a case can move, so a lease can lapse without the test waiting for one. */
    private static final class AdvanceableClock extends Clock {
        private Instant now;

        private AdvanceableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant instant) {
            this.now = instant;
        }

        private void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
