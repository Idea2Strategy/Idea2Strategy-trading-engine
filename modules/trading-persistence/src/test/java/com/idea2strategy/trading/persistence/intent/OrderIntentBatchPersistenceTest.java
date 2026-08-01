package com.idea2strategy.trading.persistence.intent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.intent.OrderIntentBatchConflictException;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OrderIntentBatchPersistenceTest {
    private static final UUID BOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EVALUATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SOURCE_BATCH_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_BOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID OTHER_EVALUATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000012");
    private static final UUID OTHER_SOURCE_BATCH_ID = UUID.fromString("30000000-0000-0000-0000-000000000013");
    private static final UUID CANDIDATE_BEFORE = UUID.fromString("35000000-0000-0000-0000-000000000003");
    private static final UUID CANDIDATE_ONE = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID CANDIDATE_TWO = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID CANDIDATE_THREE = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbcClient;
    private static PostgresOrderIntentBatchStore store;
    private static JooqOrderIntentBatchQuery query;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcClient = JdbcClient.create(dataSource);
        store = new PostgresOrderIntentBatchStore(jdbcClient, new JdbcTransactionManager(dataSource));
        query = new JooqOrderIntentBatchQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach
    void clearIntentRows() {
        jdbcClient.sql("truncate table trading.order_intent_batch cascade").update();
    }

    @Test
    void concurrentIdenticalRequestsConvergeOnOneCompleteBatch() throws Exception {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<OrderIntentBatch> create = () -> {
            ready.countDown();
            start.await();
            return store.createOrLoad(desired);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertTrue(ready.await(10, SECONDS));
            start.countDown();

            assertEquals(desired, first.get(10, SECONDS));
            assertEquals(desired, second.get(10, SECONDS));
        }
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countMappings());
        assertEquals(desired, query.findByEvaluationId(EVALUATION_ID).orElseThrow().toDomain());
    }

    @Test
    void sequentialIdenticalRetryThroughNewAdapterReturnsStoredBatch() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));

        OrderIntentBatch first = store.createOrLoad(desired);
        OrderIntentBatch restarted = newStore().createOrLoad(desired);

        assertEquals(desired, first);
        assertEquals(desired, restarted);
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countMappings());
    }

    @Test
    void sameEvaluationWithChangedBotSourceOrCandidateSetConflicts() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        store.createOrLoad(desired);

        assertAll(
                () -> assertThrows(
                        OrderIntentBatchConflictException.class,
                        () -> store.createOrLoad(desiredBatch(
                                OTHER_BOT_ID,
                                EVALUATION_ID,
                                SOURCE_BATCH_ID,
                                List.of(CANDIDATE_ONE, CANDIDATE_TWO)))),
                () -> assertThrows(
                        OrderIntentBatchConflictException.class,
                        () -> store.createOrLoad(desiredBatch(
                                BOT_ID,
                                EVALUATION_ID,
                                OTHER_SOURCE_BATCH_ID,
                                List.of(CANDIDATE_ONE, CANDIDATE_TWO)))),
                () -> assertThrows(
                        OrderIntentBatchConflictException.class,
                        () -> store.createOrLoad(desiredBatch(
                                BOT_ID,
                                EVALUATION_ID,
                                SOURCE_BATCH_ID,
                                List.of(CANDIDATE_ONE, CANDIDATE_THREE)))));
        assertEquals(desired, query.findByEvaluationId(EVALUATION_ID).orElseThrow().toDomain());
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countMappings());
    }

    @Test
    void sourceCandidateBatchReuseAcrossEvaluationsConflicts() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        store.createOrLoad(desired);

        assertThrows(
                OrderIntentBatchConflictException.class,
                () -> store.createOrLoad(desiredBatch(
                        BOT_ID,
                        OTHER_EVALUATION_ID,
                        SOURCE_BATCH_ID,
                        List.of(CANDIDATE_THREE))));

        assertFalse(query.findByEvaluationId(OTHER_EVALUATION_ID).isPresent());
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countMappings());
    }

    @Test
    void mappingConflictRollsBackNewHeaderAndEarlierMapping() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        store.createOrLoad(desired);

        assertThrows(
                OrderIntentBatchConflictException.class,
                () -> store.createOrLoad(desiredBatch(
                        BOT_ID,
                        OTHER_EVALUATION_ID,
                        OTHER_SOURCE_BATCH_ID,
                        List.of(CANDIDATE_BEFORE, CANDIDATE_ONE))));

        assertFalse(query.findByEvaluationId(OTHER_EVALUATION_ID).isPresent());
        assertEquals(1, query.countBatches());
        assertEquals(2, query.countMappings());
        assertEquals(desired, query.findByEvaluationId(EVALUATION_ID).orElseThrow().toDomain());
    }

    @Test
    void incompleteStoredMappingSetConflicts() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        store.createOrLoad(desired);
        jdbcClient.sql("delete from trading.order_intent_identity where candidate_id = :candidateId")
                .param("candidateId", CANDIDATE_TWO)
                .update();

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(desired));

        assertEquals(1, query.countBatches());
        assertEquals(1, query.countMappings());
    }

    @Test
    void changedStoredIntentIdentityConflictsEvenWhenCandidateSetMatches() {
        OrderIntentBatch desired = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        store.createOrLoad(desired);
        UUID differentIntentId = desiredBatch(
                        BOT_ID,
                        OTHER_EVALUATION_ID,
                        OTHER_SOURCE_BATCH_ID,
                        List.of(CANDIDATE_ONE))
                .intents()
                .getFirst()
                .intentId();
        jdbcClient.sql("""
                        update trading.order_intent_identity
                        set intent_id = :intentId
                        where candidate_id = :candidateId
                        """)
                .param("intentId", differentIntentId)
                .param("candidateId", CANDIDATE_ONE)
                .update();

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(desired));
    }

    @ParameterizedTest
    @MethodSource("changedHeaderFields")
    void changedStoredHeaderFieldConflicts(String column, Object changedValue) {
        OrderIntentBatch desired = desiredBatch(List.of());
        store.createOrLoad(desired);
        jdbcClient.sql("""
                        update trading.order_intent_batch
                        set %s = :changedValue
                        where evaluation_id = :evaluationId
                        """.formatted(column))
                .param("changedValue", changedValue)
                .param("evaluationId", EVALUATION_ID)
                .update();

        assertThrows(OrderIntentBatchConflictException.class, () -> newStore().createOrLoad(desired));
    }

    @Test
    void emptyBatchPersistsAndReloads() {
        OrderIntentBatch desired = desiredBatch(List.of());

        OrderIntentBatch persisted = store.createOrLoad(desired);
        OrderIntentBatch reloaded = newStore().createOrLoad(desired);

        assertEquals(desired, persisted);
        assertEquals(desired, reloaded);
        assertEquals(desired, query.findByEvaluationId(EVALUATION_ID).orElseThrow().toDomain());
        assertEquals(1, query.countBatches());
        assertEquals(0, query.countMappings());
    }

    @Test
    void reversedInputReloadsInStableSortedOrder() {
        OrderIntentBatch forward = desiredBatch(List.of(CANDIDATE_ONE, CANDIDATE_TWO));
        OrderIntentBatch reversed = desiredBatch(List.of(CANDIDATE_TWO, CANDIDATE_ONE));
        store.createOrLoad(forward);

        OrderIntentBatch reloaded = newStore().createOrLoad(reversed);
        OrderIntentBatchPersistenceView stored = query.findByEvaluationId(EVALUATION_ID).orElseThrow();

        assertEquals(reversed, reloaded);
        assertEquals(
                List.of(CANDIDATE_ONE, CANDIDATE_TWO),
                stored.intents().stream().map(identity -> identity.candidateId()).toList());
        assertEquals(forward, stored.toDomain());
    }

    private static OrderIntentBatch desiredBatch(List<UUID> candidateIds) {
        return desiredBatch(BOT_ID, EVALUATION_ID, SOURCE_BATCH_ID, candidateIds);
    }

    private static OrderIntentBatch desiredBatch(
            UUID botId,
            UUID evaluationId,
            UUID sourceBatchId,
            List<UUID> candidateIds) {
        return new OrderIntentBatchFactory().create(
                new OrderIntentBatchRequest(botId, evaluationId, sourceBatchId, candidateIds));
    }

    private static PostgresOrderIntentBatchStore newStore() {
        return new PostgresOrderIntentBatchStore(
                JdbcClient.create(dataSource),
                new JdbcTransactionManager(dataSource));
    }

    private static Stream<Arguments> changedHeaderFields() {
        OrderIntentBatch alternateIdentity = desiredBatch(
                BOT_ID,
                OTHER_EVALUATION_ID,
                OTHER_SOURCE_BATCH_ID,
                List.of());
        return Stream.of(
                Arguments.of("batch_id", alternateIdentity.batchId()),
                Arguments.of("bot_id", OTHER_BOT_ID),
                Arguments.of("source_candidate_batch_id", OTHER_SOURCE_BATCH_ID),
                Arguments.of("request_fingerprint", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
    }
}
