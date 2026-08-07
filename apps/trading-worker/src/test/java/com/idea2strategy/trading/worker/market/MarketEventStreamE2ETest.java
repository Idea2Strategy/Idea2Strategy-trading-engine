package com.idea2strategy.trading.worker.market;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.market.redis.RedisMarketEventPublisher;
import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import com.idea2strategy.trading.strategy.runtime.warmup.FeatureObservation;
import com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupFeatureSeries;
import com.idea2strategy.trading.worker.runtime.EvaluatingBotRuntime;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * RT3 end to end: the gateway publishes, the worker consumes, the bot trades.
 *
 * <p>Both halves are the real ones. The event is put on the stream by the gateway's own publisher —
 * the same Lua script, the same key, the same field layout — and taken off it by the production
 * consumer, which decodes it, feeds the RT2 loop and acknowledges. What the assertions read is the
 * canonical order the whole path was built to produce, not a call count.
 *
 * <p>The cases that matter are the ones a stream makes possible and a direct call does not:
 * redelivery of an entry a dead replica never acknowledged, an entry that fails and must stay pending
 * rather than vanish, and a group that has fallen too far behind to be allowed to decide at all.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class MarketEventStreamE2ETest {

    private static final UUID BOT = UUID.fromString("c3000000-0000-4000-8000-000000000001");
    private static final UUID RELEASE = UUID.fromString("c3000000-0000-4000-8000-000000000002");
    private static final UUID PARTITION = UUID.fromString("c3000000-0000-4000-8000-000000000003");
    private static final UUID FLOW = UUID.fromString("c3000000-0000-4000-8000-000000000004");
    private static final UUID INSTRUMENT = UUID.fromString("c3000000-0000-4000-8000-000000000005");
    private static final UUID FEE_POLICY = UUID.fromString("c3000000-0000-4000-8000-000000000006");
    private static final UUID BUFFER_POLICY = UUID.fromString("c3000000-0000-4000-8000-000000000007");
    private static final UUID ACCOUNT = UUID.fromString("c3000000-0000-4000-8000-000000000008");

    private static final String FLOW_KEY = "flow-1";
    private static final Instant ELIGIBLE_FROM = Instant.parse("2026-08-02T13:30:00Z");
    private static final Instant EVENT_AT = Instant.parse("2026-08-02T14:30:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static RedisMarketEventPublisher publisher;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static String keyPrefix;

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

    @AfterAll
    static void closeRedis() {
        if (publisher != null) {
            publisher.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @Autowired
    private EvaluatingBotRuntime runtime;

    @Autowired
    private JdbcClient jdbc;

    private RedisMarketEventStreamConsumer consumer;

    /**
     * Each case gets its own stream, its own group and an empty canonical record.
     *
     * <p>A fresh key prefix rather than a flush: the publisher's dedup set and the group's pending list
     * both outlive a case, and reusing them would have one case's acknowledgements decide another's.
     */
    @BeforeEach
    void freshStreamAndCanonicalRecord() {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        if (client == null) {
            client = RedisClient.create(uri);
            connection = client.connect();
        }
        if (publisher != null) {
            publisher.close();
        }
        keyPrefix = "rt3-" + UUID.randomUUID();
        publisher = RedisMarketEventPublisher.connect(uri, keyPrefix);
        consumer = consumer(600L);

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

    /** A published bar reaches a started bot and becomes a canonical order. */
    @Test
    void aPublishedMarketEventReachesTheStartedBotAndBecomesACanonicalOrder() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        publish(1, "84");

        int fed = consumer.pollOnce();

        assertAll(
                () -> assertEquals(1, fed),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)),
                () -> assertEquals(0, pendingEntries(), "the entry was acknowledged once it was fed"));
    }

    /**
     * A second cycle over the same stream delivers nothing: the entry is acknowledged, so it is not in
     * anyone's pending list and the group has already moved past it.
     */
    @Test
    void anAcknowledgedEntryIsNotDeliveredAgain() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        publish(1, "84");
        assertEquals(1, consumer.pollOnce());

        assertAll(
                () -> assertEquals(0, consumer.pollOnce()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    /**
     * The redelivery a consumer group exists for: a replica took the entry and died before
     * acknowledging it, and another replica finds it once the lease has passed.
     *
     * <p>Feeding it twice writes one order, not two, because the runtime derives the batch identity
     * from the event and the claim ledger recognises the second attempt.
     */
    @Test
    void anEntryLeftPendingByADeadReplicaIsReclaimedAndConvergesOnOneOrder() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        publish(1, "84");
        claimWithoutAcknowledging();

        int fed = consumer(600L, Duration.ofMillis(1)).pollOnce();

        assertAll(
                () -> assertEquals(1, fed, "the abandoned entry was reclaimed"),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT),
                        "and produced the same one order rather than a second"),
                () -> assertEquals(0, pendingEntries()));
    }

    /**
     * An entry that cannot be evaluated stays pending rather than disappearing. Acknowledging a failure
     * would lose a bar silently; leaving it pending is what makes the reclaim path an operator's retry.
     */
    @Test
    void anUndecodableEntryIsLeftPendingRatherThanLost() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        connection.sync().xadd(
                streamKey(), Map.of("eventId", "evt_broken", "schemaVersion", "1"));

        int fed = consumer.pollOnce();

        assertAll(
                () -> assertEquals(0, fed),
                () -> assertEquals(1, pendingEntries(), "still owed to someone, not thrown away"),
                () -> assertEquals(0, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    /**
     * C09: a group too far behind stops feeding. A bot deciding on a bar that is minutes old is
     * deciding on a market that has moved on, and the canonical order it produces would be real.
     */
    @Test
    void aGroupTooFarBehindStopsFeedingRatherThanDecidingOnStaleBars() {
        runtime.start(plan(), warmup(), EvaluationWindow.openEndedFrom(ELIGIBLE_FROM));
        publish(1, "84");
        publish(2, "83");

        int fed = consumer(1L).pollOnce();

        assertAll(
                () -> assertEquals(0, fed, "nothing new is started while the lag is beyond the maximum"),
                () -> assertEquals(0, count(
                        "select count(*) from trading.order_intents where bot_id = ?", BOT)));
    }

    // ------------------------------------------------------------------ fixtures

    private RedisMarketEventStreamConsumer consumer(long maximumEntryLag) {
        return consumer(maximumEntryLag, Duration.ofSeconds(60));
    }

    private RedisMarketEventStreamConsumer consumer(long maximumEntryLag, Duration reclaimAfter) {
        return new RedisMarketEventStreamConsumer(
                connection.sync(), runtime, streamKey(), "worker-under-test", 128, reclaimAfter,
                maximumEntryLag);
    }

    private static String streamKey() {
        return "{" + keyPrefix + ":market}:events";
    }

    /** Takes the entry as another replica would and never acknowledges it. */
    private void claimWithoutAcknowledging() {
        var commands = connection.sync();
        commands.xgroupCreate(
                io.lettuce.core.XReadArgs.StreamOffset.from(streamKey(), "0-0"),
                RedisMarketEventStreamConsumer.CONSUMER_GROUP,
                io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
        commands.xreadgroup(
                io.lettuce.core.Consumer.from(
                        RedisMarketEventStreamConsumer.CONSUMER_GROUP, "replica-that-died"),
                io.lettuce.core.XReadArgs.StreamOffset.lastConsumed(streamKey()));
    }

    private long pendingEntries() {
        var pending = connection.sync().xpending(
                streamKey(), RedisMarketEventStreamConsumer.CONSUMER_GROUP);
        return pending == null ? 0L : pending.getCount();
    }

    /** Publishes through the gateway's own publisher, so the layout under test is the real one. */
    private void publish(long sequence, String close) {
        var envelope = new MarketEventEnvelope(
                "evt_rt3_" + sequence, 2, INSTRUMENT, "ALPACA", "SIP", MarketEventType.MARKET_EVALUATION_READY,
                "provider-" + sequence, EVENT_AT, EVENT_AT, sequence, 0, null,
                evaluationValues(close));
        publisher.publish(new com.idea2strategy.trading.market.alpaca.MarketEventHandlingResult(
                com.idea2strategy.trading.market.alpaca.MarketEventHandlingStatus.APPLIED,
                envelope,
                sequence,
                true,
                true));
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

    private LoadedExecutionPlan plan() {
        return new LoadedExecutionPlan(
                BOT, RELEASE, "basic-compiled-plan.v1", Set.of(), planDocument(),
                "strategy-bot-runtime.v1", 0, Map.of());
    }

    private PreparedWarmup warmup() {
        List<FeatureObservation> observations = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            observations.add(new FeatureObservation(
                    INSTRUMENT.toString(),
                    ELIGIBLE_FROM.minusSeconds(60L * (14 - index)),
                    new BigDecimal(100 - index)));
        }
        return new PreparedWarmup(
                "manifest-rt3", "dataset-rt3", 1, "a".repeat(64),
                Map.of("rsi-14-pt1m", new WarmupFeatureSeries(
                        "rsi-14-pt1m", "RSI_14", "1.0.0", "PT1M", "manifest-rt3", "a".repeat(64),
                        observations)));
    }

    private static String planDocument() {
        return """
                {"contractVersion":"strategy-bot.v1","schemaVersion":"basic-compiled-plan.v1",\
                "elementCatalogVersion":"basic-elements:2026-08-04",\
                "instrumentCatalogVersion":"us-supported-universe:2026-08-04",\
                "compilerVersion":"basic-compiler:1.0.0",\
                "requiredFeatureSetHash":"sha256:%s","requiredFeatures":[{"requirementId":"rsi-14-pt1m",\
                "featureId":"c3000000-0000-4000-8000-000000000401","featureVersion":"1.0.0",\
                "instruments":["%s"],"resolution":"PT1M","requiredObservations":14}],\
                "executionSnapshot":{"immutableStrategyVersion":{\
                "snapshotSchemaVersion":"basic-launch-snapshot.v1","semanticHash":"sha256:%s",\
                "snapshotHash":"sha256:%s"},"mode":"BASIC","initialCashAmount":"100000.00000000",\
                "currency":"USD","partitions":[{"key":"partition-1","budgetCapBps":10000,\
                "flows":[{"key":"%s","officialInstrumentIds":["%s"]}]}]},\
                "steps":[{"sequence":1,"operation":"LOAD_FEATURE",\
                "arguments":{"feature":"RSI_14","resolution":"1m"}},{"sequence":2,"operation":"COMPARE",\
                "arguments":{"operator":"LT","threshold":"30"}},{"sequence":3,\
                "operation":"EMIT_ORDER_CANDIDATE",\
                "arguments":{"allocation":"EQUAL","orderType":"MARKET","side":"BUY"}}],\
                "planChecksum":"sha256:%s"}"""
                .formatted("3".repeat(64), INSTRUMENT, "2".repeat(64), "1".repeat(64),
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
                    values ('%s', '%s', 'BASIC', 'RT3 bot', 'RUNNING', '2026-08-01T00:00:00+00',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(BOT, ACCOUNT));
            statement.addBatch("""
                    insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps,
                        calculation_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'rt3', 20, 'v1', 'sha256:rt3-fee',
                        '2026-08-01T00:00:00+00', '2026-08-01T00:00:00+00')
                    """.formatted(FEE_POLICY));
            statement.addBatch("""
                    insert into trading.buying_power_buffer_policy_versions (id, policy_code, version,
                        buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at)
                    values ('%s', 'OFFICIAL', 'rt3', 100, 'v1', 'sha256:rt3-buffer',
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
                    values ('%s', '%s', 'RT3 strategy', 10000, 0, 0, '%s')
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
                    values ('%s', 'USD', 100000, 0, 0, 0, 0, '2026-08-02T13:00:00+00', 'VALUED', 1,
                        'rt3-bot', '2026-08-02T13:00:00+00')
                    """.formatted(BOT));
            statement.addBatch("""
                    insert into trading.partition_budget_projections (partition_id, bot_id,
                        currency_code, budget_cap_amount, active_reservation_amount, invested_amount,
                        segregated_short_proceeds_amount, short_collateral_amount, valuation_at,
                        valuation_status, last_event_sequence, projection_hash, updated_at)
                    values ('%s', '%s', 'USD', 100000, 0, 0, 0, 0, '2026-08-02T13:00:00+00', 'VALUED',
                        1, 'rt3-partition', '2026-08-02T13:00:00+00')
                    """.formatted(PARTITION, BOT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed the market event stream parents", failure);
        }
    }

    private long count(String sql, Object argument) {
        return jdbc.sql(sql).param(argument).query(Long.class).single();
    }
}
