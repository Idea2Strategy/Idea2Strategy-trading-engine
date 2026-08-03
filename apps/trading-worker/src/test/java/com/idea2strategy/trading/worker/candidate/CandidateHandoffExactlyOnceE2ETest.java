package com.idea2strategy.trading.worker.candidate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessingResult;
import com.idea2strategy.trading.application.candidate.CandidateBatchProcessor;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.intent.IntentDecision;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import com.idea2strategy.trading.domain.intent.OrderIntentRequest;
import com.idea2strategy.trading.domain.order.Order;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.domain.settlement.Settlement;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.fixture.v1.ContractJsonFixtureLoaderV1;
import com.idea2strategy.trading.persistence.candidate.CandidateBatchProcessingStatus;
import com.idea2strategy.trading.persistence.candidate.JooqCandidateBatchQuery;
import com.idea2strategy.trading.persistence.candidate.PostgresCandidateBatchClaimAdapter;
import com.idea2strategy.trading.persistence.canonical.CanonicalBaseline;
import com.idea2strategy.trading.persistence.intent.PostgresOrderIntentBatchStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * F90: C's evaluation output is processed exactly once, whatever the queue does to it.
 *
 * <p>The payloads here are C's own contract fixtures, read by the same deserialisation and the same
 * {@link OrderCandidateBatchAdapter} the worker uses, and the claim ledger is the real canonical
 * table on a real PostgreSQL. What varies is delivery: a batch arrives after a newer one, arrives
 * twice, arrives on two consumers at the same instant, or arrives again after its first processing
 * failed. In every case the effect must be one processing per batch — and for the scoped version 2
 * payload, one canonical intent batch.
 *
 * <p>Redelivery of a batch whose first claim is still in flight reports {@code DUPLICATE} rather
 * than waiting, because the lease is what decides who is working; a real queue redelivers later and
 * finds either a completed claim or an expired lease. Both ends of that are covered here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "trading.fake-candidate.enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.table=flyway_schema_history_private",
        "spring.flyway.baseline-on-migrate=true"
})
class CandidateHandoffExactlyOnceE2ETest {

    /** The scoped fixture's own identifiers; the canonical parents below carry exactly these. */
    private static final UUID FIXTURE_BOT = UUID.fromString("e332fd66-3a21-4d3e-8a2a-4c2e4ee55430");
    private static final UUID FIXTURE_PARTITION = UUID.fromString("1f0a5b6c-8d2e-4a71-9c33-2b5e7d901aa4");
    private static final UUID FIXTURE_FLOW = UUID.fromString("9b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d");
    private static final UUID FIXTURE_EVALUATION = UUID.fromString("626825b7-9de7-447a-a775-d8840fd24e55");
    private static final UUID FIXTURE_EVENT = UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f");
    private static final UUID FIXTURE_INSTRUMENT = UUID.fromString("8a35e6b5-cf84-4f63-920d-57c1f1b95df0");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        CanonicalBaseline.migrateWithContributions(dataSource);
        seedFixtureParents(dataSource);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The scoped fixture names real rows of other services, so its canonical intent write needs
     * parents with exactly those identifiers. Seeded with referential triggers off, as every
     * canonical contract fixture is.
     */
    private static void seedFixtureParents(DriverManagerDataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.addBatch("""
                    insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at)
                    values ('f90a0000-0000-4000-8000-000000000001', 'ACTIVE',
                        '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00')
                    """);
            statement.addBatch("""
                    insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,
                        lifecycle_changed_at, created_at, execution_eligible_from)
                    values ('%s', 'f90a0000-0000-4000-8000-000000000001', 'BASIC', 'F90 bot',
                        'RUNNING', '2026-07-01T00:00:00+00', '2026-07-01T00:00:00+00',
                        '2026-07-01T00:00:00+00')
                    """.formatted(FIXTURE_BOT));
            statement.addBatch("""
                    insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps,
                        position_x, position_y, configuration_hash)
                    values ('%s', '%s', 'Partition', 10000, 0, 0, '%s')
                    """.formatted(FIXTURE_PARTITION, FIXTURE_BOT, "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.flows (id, partition_id, name, element_catalog_version_id,
                        compiled_flow_plan_id, position_x, position_y, semantic_document,
                        layout_document, layout_schema_version, semantic_hash, layout_hash,
                        configuration_hash)
                    values ('%s', '%s', 'Flow', gen_random_uuid(), gen_random_uuid(), 0, 0,
                        '{}', '{}', 'v1', '%s', '%s', '%s')
                    """.formatted(FIXTURE_FLOW, FIXTURE_PARTITION,
                            "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            statement.addBatch("""
                    insert into bot.bot_events (id, bot_id, event_sequence, event_type,
                        event_schema_version, correlation_id, idempotency_key, occurred_at,
                        received_at, summary_document)
                    values ('%s', '%s', 1, 'ORDER_LIFECYCLE', 'v1', gen_random_uuid(),
                        'f90-source-event', '2026-07-31T14:30:00+00', '2026-07-31T14:30:00+00', '{}')
                    """.formatted(FIXTURE_EVENT, FIXTURE_BOT));
            statement.addBatch("""
                    insert into bot.evaluation_runs (id, bot_id, partition_id, flow_id,
                        trigger_event_id, status, queued_at)
                    values ('%s', '%s', '%s', '%s', '%s', 'RUNNING', '2026-07-31T14:30:00+00')
                    """.formatted(FIXTURE_EVALUATION, FIXTURE_BOT, FIXTURE_PARTITION,
                            FIXTURE_FLOW, FIXTURE_EVENT));
            statement.addBatch("""
                    insert into market_data.instruments (id, asset_type, primary_exchange_mic,
                        currency_code)
                    values ('%s', 'STOCK', 'XNAS', 'USD')
                    """.formatted(FIXTURE_INSTRUMENT));
            statement.executeBatch();
            statement.execute("set session_replication_role = origin");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("unable to seed the fixture's parents", failure);
        }
    }

    @Autowired
    private PostgresCandidateBatchClaimAdapter claimAdapter;

    @Autowired
    private CandidateBatchStatusPort statusPort;

    @Autowired
    private JooqCandidateBatchQuery query;

    @Autowired
    private PostgresOrderIntentBatchStore intentStore;

    @Autowired
    private com.idea2strategy.trading.application.port.BotStopSettlementStore stopSettlements;

    @Autowired
    private JdbcClient jdbcClient;

    private final OrderCandidateBatchAdapter adapter = new OrderCandidateBatchAdapter();

    /**
     * The version 1 and version 2 fixtures deliberately share a batch id — they are the same
     * logical batch in two schema generations — and the tests here share one database. Each test
     * therefore clears the claims of exactly the batches it delivers, so it proves its own
     * sequence rather than the accident of test ordering.
     */
    private void resetClaims(UUID... batchIds) {
        for (UUID batchId : batchIds) {
            jdbcClient.sql("delete from trading.candidate_batch_processing where batch_id = :id")
                    .param("id", batchId)
                    .update();
        }
    }

    /**
     * The out-of-order and duplicate case in one sequence: the newer batch B arrives first, the
     * older fixture batch A arrives after it, then both arrive again. Two batches, four deliveries,
     * two processings.
     */
    @Test
    void anOlderBatchArrivingLateAndEveryBatchArrivingTwiceProcessExactlyOnce() {
        CandidateBatch older = adapter.toDomain(scopedFixture());
        CandidateBatch newer = adapter.toDomain(programmaticBatch(
                UUID.fromString("f90b0000-0000-4000-8000-00000000000b"),
                older.createdAt().plusSeconds(60)));
        resetClaims(older.batchId(), newer.batchId());
        CountingPorts ports = new CountingPorts();
        CandidateBatchProcessor processor = processor(ports);

        var results = List.of(
                processor.process(newer),
                processor.process(older),
                processor.process(older),
                processor.process(newer));

        assertAll(
                () -> assertEquals(List.of(
                        CandidateBatchProcessingResult.PROCESSED,
                        CandidateBatchProcessingResult.PROCESSED,
                        CandidateBatchProcessingResult.DUPLICATE,
                        CandidateBatchProcessingResult.DUPLICATE), results),
                () -> assertEquals(1, ports.placements(older.candidates().getFirst().candidateId())),
                () -> assertEquals(1, ports.placements(newer.candidates().getFirst().candidateId())),
                () -> assertEquals(CandidateBatchProcessingStatus.COMPLETED,
                        query.findByBatchId(older.batchId()).orElseThrow().status()),
                () -> assertEquals(CandidateBatchProcessingStatus.COMPLETED,
                        query.findByBatchId(newer.batchId()).orElseThrow().status()));
    }

    /** Two consumers claiming the same batch at the same instant: the database picks one. */
    @Test
    void simultaneousDeliveryToTwoConsumersClaimsOnlyOnce() throws Exception {
        CandidateBatch batch = adapter.toDomain(programmaticBatch(
                UUID.fromString("f90c0000-0000-4000-8000-00000000000c"),
                Instant.parse("2026-07-31T14:31:00Z")));
        CountingPorts ports = new CountingPorts();
        CandidateBatchProcessor processor = processor(ports);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<CandidateBatchProcessingResult>> futures;
        try (var executor = Executors.newFixedThreadPool(2)) {
            futures = List.of(
                    executor.submit(() -> raceProcess(processor, batch, start)),
                    executor.submit(() -> raceProcess(processor, batch, start)));
            start.countDown();
            for (Future<CandidateBatchProcessingResult> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        var outcomes = List.of(futures.get(0).get(), futures.get(1).get());
        assertAll(
                () -> assertTrue(outcomes.contains(CandidateBatchProcessingResult.PROCESSED)),
                () -> assertTrue(outcomes.contains(CandidateBatchProcessingResult.DUPLICATE)),
                () -> assertEquals(1, ports.placements(batch.candidates().getFirst().candidateId())));
    }

    /** A failed first processing is retried by redelivery, not doubled. */
    @Test
    void aFailedProcessingIsRetriedByRedeliveryWithoutDoubleEffect() {
        CandidateBatch batch = adapter.toDomain(programmaticBatch(
                UUID.fromString("f90d0000-0000-4000-8000-00000000000d"),
                Instant.parse("2026-07-31T14:32:00Z")));
        CountingPorts ports = new CountingPorts();
        ports.failNextPlacement("simulated broker outage");
        CandidateBatchProcessor processor = processor(ports);

        assertThrows(IllegalStateException.class, () -> processor.process(batch));
        assertEquals(CandidateBatchProcessingStatus.FAILED,
                query.findByBatchId(batch.batchId()).orElseThrow().status());

        var retried = processor.process(batch);

        assertAll(
                () -> assertEquals(CandidateBatchProcessingResult.PROCESSED, retried),
                () -> assertEquals(1, ports.placements(batch.candidates().getFirst().candidateId())),
                () -> assertEquals(CandidateBatchProcessingStatus.COMPLETED,
                        query.findByBatchId(batch.batchId()).orElseThrow().status()));
    }

    /**
     * The scoped payload's whole point: composed into a canonical intent batch, a redelivered
     * composition converges on the same single row instead of writing a second one.
     */
    @Test
    void theScopedPayloadLandsOneCanonicalIntentBatchUnderRedelivery() {
        CandidateBatch batch = adapter.toDomain(scopedFixture());
        assertTrue(batch.carriesPartitionScope(), "the v2 fixture must carry the scope");
        OrderIntentBatchRequest request = intentRequest(batch);
        OrderIntentBatchFactory factory = new OrderIntentBatchFactory();

        OrderIntentBatch first = intentStore.createOrLoad(factory.create(request));
        OrderIntentBatch redelivered = intentStore.createOrLoad(factory.create(request));

        assertAll(
                () -> assertEquals(first.batchId(), redelivered.batchId()),
                () -> assertEquals(1, count(
                        "select count(*) from trading.order_intent_batches where id = ?",
                        first.batchId())),
                () -> assertEquals(batch.candidates().size(), count(
                        "select count(*) from trading.order_intents where batch_id = ?",
                        first.batchId())));
    }

    /** Version 1 still processes exactly once; it simply can never become canonical. */
    @Test
    void theUnscopedVersionOnePayloadStillProcessesExactlyOnce() {
        CandidateBatch batch = adapter.toDomain(ContractJsonFixtureLoaderV1.readResource(
                "contracts/v1/order-candidate-batch.json",
                new TypeReference<OrderCandidateBatch>() {}));
        assertEquals(false, batch.carriesPartitionScope());
        resetClaims(batch.batchId());
        CountingPorts ports = new CountingPorts();
        CandidateBatchProcessor processor = processor(ports);

        var first = processor.process(batch);
        var second = processor.process(batch);

        assertAll(
                () -> assertEquals(CandidateBatchProcessingResult.PROCESSED, first),
                () -> assertEquals(CandidateBatchProcessingResult.DUPLICATE, second),
                () -> assertEquals(1, ports.placements(batch.candidates().getFirst().candidateId())));
    }

    // ---------------------------------------------------------------- fixtures

    private static OrderCandidateBatch scopedFixture() {
        return ContractJsonFixtureLoaderV1.readResource(
                "contracts/v2/order-candidate-batch.json",
                new TypeReference<OrderCandidateBatch>() {});
    }

    /**
     * A second scoped batch, shaped exactly as the contract record enforces. Its identifiers are
     * its own because two deliveries of different batches must never be mistaken for a duplicate.
     */
    private static OrderCandidateBatch programmaticBatch(UUID batchId, Instant createdAt) {
        UUID candidateId = UUID.nameUUIDFromBytes(
                ("f90-candidate:" + batchId).getBytes(StandardCharsets.UTF_8));
        return new OrderCandidateBatch(
                OrderCandidateBatch.SCOPED_SCHEMA_VERSION,
                batchId,
                UUID.nameUUIDFromBytes(("f90-evaluation:" + batchId).getBytes(StandardCharsets.UTF_8)),
                FIXTURE_BOT,
                FIXTURE_PARTITION,
                FIXTURE_EVENT,
                createdAt,
                List.of(new OrderCandidate(
                        candidateId,
                        FIXTURE_INSTRUMENT,
                        FIXTURE_FLOW,
                        com.idea2strategy.trading.messaging.evaluation.OrderSide.BUY,
                        new BigDecimal("1"),
                        null,
                        List.of("BASIC_RULE_MATCHED"))));
    }

    /**
     * F's single-intent composition over the payload's own values. The limit price decides the
     * order type because a candidate that names a price is asking for one.
     */
    private static OrderIntentBatchRequest intentRequest(CandidateBatch batch) {
        List<OrderIntentRequest> intents = batch.candidates().stream()
                .map(candidate -> new OrderIntentRequest(
                        candidate.candidateId(),
                        candidate.flowId(),
                        candidate.instrumentId(),
                        OrderSide.valueOf(candidate.side()),
                        OrderPositionEffect.INCREASE_LONG,
                        candidate.limitPrice() == null ? OrderType.MARKET : OrderType.LIMIT,
                        TimeInForce.DAY,
                        candidate.quantity(),
                        candidate.limitPrice(),
                        null,
                        null,
                        IntentDecision.APPROVED,
                        "ELIGIBLE",
                        candidate.quantity()))
                .toList();
        return new OrderIntentBatchRequest(
                batch.botId(),
                batch.partitionId(),
                batch.sourceEventId(),
                batch.evaluationId(),
                batch.batchId(),
                batch.createdAt(),
                intents);
    }

    private CandidateBatchProcessor processor(CountingPorts ports) {
        return new CandidateBatchProcessor(
                claimAdapter, statusPort, ports, ports, ports, stopSettlements);
    }

    private static CandidateBatchProcessingResult raceProcess(
            CandidateBatchProcessor processor, CandidateBatch batch, CountDownLatch start)
            throws InterruptedException {
        start.await();
        return processor.process(batch);
    }

    private long count(String sql, UUID id) {
        return jdbcClient.sql(sql).param(id).query(Long.class).single();
    }

    /**
     * Order, execution and settlement as counters. The canonical write chain has its own proofs;
     * what this boundary owes is that each candidate reaches it exactly once, and a counter is the
     * shape of evidence a duplicate cannot hide from.
     */
    private static final class CountingPorts implements OrderPort, ExecutionPort, SettlementPort {
        private final Map<UUID, AtomicInteger> placements = new ConcurrentHashMap<>();
        private volatile String failNextReason;

        void failNextPlacement(String reason) {
            this.failNextReason = reason;
        }

        int placements(UUID candidateId) {
            AtomicInteger counter = placements.get(candidateId);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public Order place(com.idea2strategy.trading.domain.candidate.CandidateOrder candidate) {
            String reason = failNextReason;
            if (reason != null) {
                failNextReason = null;
                throw new IllegalStateException(reason);
            }
            placements.computeIfAbsent(candidate.candidateId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return new Order(
                    stableId("order", candidate.candidateId()),
                    candidate.candidateId(),
                    candidate.instrumentId(),
                    candidate.side(),
                    candidate.quantity(),
                    candidate.limitPrice());
        }

        @Override
        public Execution execute(Order order) {
            return new Execution(
                    stableId("execution", order.orderId()),
                    order.orderId(),
                    order.quantity(),
                    order.limitPrice() == null ? BigDecimal.ONE : order.limitPrice());
        }

        @Override
        public Settlement settle(Execution execution) {
            return new Settlement(
                    stableId("settlement", execution.executionId()),
                    execution.executionId());
        }

        private static UUID stableId(String kind, UUID sourceId) {
            return UUID.nameUUIDFromBytes((kind + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
