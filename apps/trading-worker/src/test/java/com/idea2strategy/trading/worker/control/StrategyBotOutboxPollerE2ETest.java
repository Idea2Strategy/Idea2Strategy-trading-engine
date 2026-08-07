package com.idea2strategy.trading.worker.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.strategy.runtime.control.BotControlCheckpointStore;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotContractCodec;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotControlConsumer;
import com.idea2strategy.trading.strategy.runtime.control.StrategyBotExecutionPlanAdapter;
import com.idea2strategy.trading.strategy.runtime.plan.ExecutionPlanCompatibility;
import com.idea2strategy.trading.strategy.runtime.plan.LoadedExecutionPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * RT5: B's strategy-bot commands reach the control consumer through the backend's own transactional
 * outbox, with {@code operations.outbox_consumer_receipts} as the delivery bookkeeping and the
 * consumer's checkpoint derived from those same receipts.
 *
 * <p>The payloads are the strategy-bot.v1 shapes B's adapters write, on the real canonical tables.
 * Everything the consumer's in-memory tests proved is re-proven here across a process boundary: a
 * command already completed is never delivered twice, a run whose receipt is lost is still refused
 * because the derived checkpoint remembers the stop, a stop produced before a run wins, and a command
 * the consumer cannot handle retries on its backoff and then fails permanently rather than blocking
 * every other bot's commands behind it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class StrategyBotOutboxPollerE2ETest {

    private static final UUID RUN_BOT = UUID.fromString("57000000-0000-4000-8000-000000000001");
    private static final UUID ORDER_BOT = UUID.fromString("57000000-0000-4000-8000-000000000002");
    private static final UUID POISON_BOT = UUID.fromString("57000000-0000-4000-8000-000000000003");
    private static final String SNAPSHOT_HASH = "1".repeat(64);
    private static final Instant T0 = Instant.parse("2026-08-03T14:30:00Z");
    private static final String HANDLER = StrategyBotControlTransportConfiguration.HANDLER_ID;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        CanonicalBaseline.migrateWithContributions(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcClient jdbc;

    private BotControlCheckpointStore checkpoints;
    private final List<String> lifecycleEvents = new ArrayList<>();
    private MutableClock clock;
    private StrategyBotOutboxPoller poller;

    @BeforeEach
    void wire() {
        jdbc.sql("delete from operations.outbox_consumer_receipts").update();
        jdbc.sql("delete from operations.outbox_messages").update();
        lifecycleEvents.clear();
        checkpoints = new OutboxReceiptBotControlCheckpointStore(jdbc, HANDLER);
        clock = new MutableClock(T0);
        StrategyBotControlConsumer consumer = new StrategyBotControlConsumer(
                new StrategyBotContractCodec(),
                botId -> POISON_BOT.equals(botId) ? Optional.empty() : Optional.of(compiledPlan()),
                checkpoints,
                new RecordingLifecycle(),
                (request, starter) -> {
                    starter.accept(null);
                    return null;
                },
                new ExecutionPlanCompatibility(
                        "basic-compiled-plan.v1",
                        StrategyBotExecutionPlanAdapter.RUNTIME_SCHEMA_VERSION,
                        Map.of()));
        poller = new StrategyBotOutboxPoller(
                jdbc, consumer, clock, HANDLER, "rt5-e2e-worker", 10,
                Duration.ofSeconds(30), Duration.ofSeconds(60), 3);
    }

    @Test
    void deliversRunThenStopOnceAndNeverAgain() {
        seedCommand(RUN_BOT, 1, "RUN");
        clock.advance(Duration.ofSeconds(1));
        seedCommand(RUN_BOT, 2, "STOP");

        assertEquals(2, poller.pollOnce());
        assertAll(
                () -> assertEquals(List.of("start:" + RUN_BOT, "stop:" + RUN_BOT), lifecycleEvents),
                () -> assertEquals("STOPPED", checkpoints.find(RUN_BOT).orElseThrow().status().name()),
                () -> assertEquals(2, checkpoints.find(RUN_BOT).orElseThrow().lastAggregateSequence()),
                () -> assertEquals(2, completedReceipts(RUN_BOT)));

        // A completed receipt is what makes a later cycle deliver nothing at all.
        clock.advance(Duration.ofSeconds(120));
        assertEquals(0, poller.pollOnce());
        assertEquals(2, lifecycleEvents.size());
    }

    /**
     * The stronger property: even with the run's receipt gone — the crash window between consuming
     * and committing — the derived checkpoint still remembers the stop, so the redelivered run is
     * refused rather than restarting a stopped bot.
     */
    @Test
    void aRedeliveredRunIsRefusedByTheDerivedCheckpoint() {
        seedCommand(RUN_BOT, 1, "RUN");
        clock.advance(Duration.ofSeconds(1));
        seedCommand(RUN_BOT, 2, "STOP");
        assertEquals(2, poller.pollOnce());

        jdbc.sql("""
                        delete from operations.outbox_consumer_receipts
                        where consumer_handler_id = :handler
                          and outbox_message_id = :messageId
                        """)
                .param("handler", HANDLER)
                .param("messageId", messageId(RUN_BOT, 1))
                .update();
        clock.advance(Duration.ofSeconds(120));

        assertEquals(1, poller.pollOnce());
        assertAll(
                () -> assertEquals(List.of("start:" + RUN_BOT, "stop:" + RUN_BOT), lifecycleEvents),
                () -> assertEquals("STOPPED", checkpoints.find(RUN_BOT).orElseThrow().status().name()),
                () -> assertEquals(2, completedReceipts(RUN_BOT)));
    }

    /** Commands are delivered in the order B produced them, so a stop cannot be overtaken. */
    @Test
    void aStopProducedBeforeTheRunMakesTheRunANoOp() {
        seedCommand(ORDER_BOT, 2, "STOP");
        clock.advance(Duration.ofSeconds(1));
        seedCommand(ORDER_BOT, 3, "RUN");

        assertEquals(2, poller.pollOnce());
        assertAll(
                () -> assertEquals(List.of("stop:" + ORDER_BOT), lifecycleEvents),
                () -> assertEquals("STOPPED", checkpoints.find(ORDER_BOT).orElseThrow().status().name()),
                () -> assertEquals(2, completedReceipts(ORDER_BOT)));
    }

    @Test
    void anUnhandleableCommandRetriesWithBackoffThenFailsPermanently() {
        seedCommand(POISON_BOT, 1, "RUN");

        assertEquals(0, poller.pollOnce());
        assertAll(
                () -> assertEquals("RETRYABLE_FAILURE", receiptStatus(POISON_BOT)),
                () -> assertEquals("StrategyBotControlException", failureCode(POISON_BOT)),
                () -> assertEquals(1, attemptCount(POISON_BOT)));

        // Inside the backoff the receipt is not due, so a cycle leaves it untouched.
        assertEquals(0, poller.pollOnce());
        assertEquals(1, attemptCount(POISON_BOT));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, poller.pollOnce());
        assertAll(
                () -> assertEquals("RETRYABLE_FAILURE", receiptStatus(POISON_BOT)),
                () -> assertEquals(2, attemptCount(POISON_BOT)));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, poller.pollOnce());
        assertAll(
                () -> assertEquals("PERMANENT_FAILURE", receiptStatus(POISON_BOT)),
                () -> assertEquals(3, attemptCount(POISON_BOT)),
                () -> assertTrue(lifecycleEvents.isEmpty()));

        // A permanently failed receipt is never reclaimed, however long the worker runs.
        clock.advance(Duration.ofHours(1));
        assertEquals(0, poller.pollOnce());
        assertEquals(3, attemptCount(POISON_BOT));
    }

    // ------------------------------------------------------------------ seeding and reads

    private void seedCommand(UUID botId, long sequence, String kind) {
        jdbc.sql("""
                        insert into operations.outbox_messages (
                            id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                            event_schema_version, payload_document, idempotency_key, created_at)
                        values (:id, 'strategy-bot', :botId, :sequence, :eventType,
                            'strategy-bot.v1', cast(:payload as jsonb), :idempotencyKey, :createdAt)
                        """)
                .param("id", messageId(botId, sequence))
                .param("botId", botId)
                .param("sequence", sequence)
                .param("eventType", "RUN".equals(kind) ? "BOT_RUN_COMMAND" : "BOT_STOP_COMMAND")
                .param("payload", "RUN".equals(kind)
                        ? runPayload(botId, sequence)
                        : stopPayload(botId, sequence))
                .param("idempotencyKey", idempotencyKey(botId, sequence))
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private String runPayload(UUID botId, long sequence) {
        return """
                {"metadata":{"contractVersion":"strategy-bot.v1","messageType":"BOT_RUN_COMMAND",\
                "messageId":"%s","occurredAt":"%s","correlationId":"%s","idempotencyKey":"%s"},\
                "botId":"%s","expectedSnapshotHash":"sha256:%s","executionEligibleFrom":"%s"}"""
                .formatted(messageId(botId, sequence), T0, botId, idempotencyKey(botId, sequence),
                        botId, SNAPSHOT_HASH, T0);
    }

    private String stopPayload(UUID botId, long sequence) {
        return """
                {"metadata":{"contractVersion":"strategy-bot.v1","messageType":"BOT_STOP_COMMAND",\
                "messageId":"%s","occurredAt":"%s","correlationId":"%s","idempotencyKey":"%s"},\
                "botId":"%s","expectedSnapshotHash":"sha256:%s","reasonCode":"USER_REQUESTED"}"""
                .formatted(messageId(botId, sequence), T0, botId, idempotencyKey(botId, sequence),
                        botId, SNAPSHOT_HASH);
    }

    /**
     * The checksum-pinned compiled plan {@code BotStopCommandSettlementE2ETest} already carries. The
     * document is bot-independent, so one valid contract serves every bot in this suite.
     */
    private static String compiledPlan() {
        UUID instrument = UUID.fromString("f9160000-0000-4000-8000-000000000001");
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
                "planChecksum":"sha256:3074991f2c223c31761ba3bc144d392383ddb24aaf756d94391ba0a08c1146de"}
                """.formatted("3".repeat(64), UUID.fromString("f91f0000-0000-4000-8000-000000000001"),
                        instrument, "2".repeat(64), SNAPSHOT_HASH, instrument);
    }

    private static UUID messageId(UUID botId, long sequence) {
        return UUID.nameUUIDFromBytes(("rt5-message:" + botId + ":" + sequence)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String idempotencyKey(UUID botId, long sequence) {
        return "sha256:" + sha256("rt5:" + botId + ":" + sequence);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private int completedReceipts(UUID botId) {
        return receiptQuery("select count(*)", botId, "and receipt.status = 'COMPLETED'")
                .query(Integer.class).single();
    }

    private String receiptStatus(UUID botId) {
        return receiptQuery("select cast(receipt.status as varchar)", botId, "")
                .query(String.class).single();
    }

    private String failureCode(UUID botId) {
        return receiptQuery("select receipt.failure_code", botId, "").query(String.class).single();
    }

    private int attemptCount(UUID botId) {
        return receiptQuery("select receipt.receive_attempt_count", botId, "")
                .query(Integer.class).single();
    }

    private JdbcClient.StatementSpec receiptQuery(String projection, UUID botId, String extra) {
        return jdbc.sql(projection + """
                         from operations.outbox_consumer_receipts receipt
                         join operations.outbox_messages message on message.id = receipt.outbox_message_id
                         where receipt.consumer_handler_id = :handler and message.aggregate_id = :botId
                        """ + extra)
                .param("handler", HANDLER)
                .param("botId", botId);
    }

    private final class RecordingLifecycle
            implements com.idea2strategy.trading.strategy.runtime.control.BotRuntimeLifecycle {
        @Override
        public void start(LoadedExecutionPlan plan,
                com.idea2strategy.trading.strategy.runtime.warmup.PreparedWarmup warmup,
                com.idea2strategy.trading.strategy.runtime.control.EvaluationWindow window) {
            lifecycleEvents.add("start:" + plan.botId());
        }

        @Override
        public void stop(UUID botId, String reasonCode) {
            lifecycleEvents.add("stop:" + botId);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
