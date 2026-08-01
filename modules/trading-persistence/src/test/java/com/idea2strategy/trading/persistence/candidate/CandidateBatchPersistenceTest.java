package com.idea2strategy.trading.persistence.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CandidateBatchPersistenceTest {
    private static final UUID BATCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static PostgresCandidateBatchClaimAdapter claimAdapter;
    private static JooqCandidateBatchQuery query;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        claimAdapter = new PostgresCandidateBatchClaimAdapter(JdbcClient.create(dataSource));
        query = new JooqCandidateBatchQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach
    void clearProcessingRows() {
        JdbcClient.create(dataSource).sql("truncate table trading.candidate_batch_processing").update();
    }

    @Test
    void duplicateBatchClaimCreatesOneProcessingRow() {
        CandidateBatch batch = candidateBatch();

        boolean first = claimAdapter.claim(batch);
        boolean duplicate = claimAdapter.claim(batch);

        assertTrue(first);
        assertFalse(duplicate);
        assertEquals(1, query.count());
        assertEquals(
                new CandidateBatchProcessingView(
                        BATCH_ID,
                        batch.evaluationId(),
                        CandidateBatchProcessingStatus.PROCESSING,
                        null),
                query.findByBatchId(BATCH_ID).orElseThrow());
    }

    @Test
    void concurrentBatchClaimsHaveOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> claim = () -> {
            ready.countDown();
            start.await();
            return claimAdapter.claim(candidateBatch());
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            ready.await();
            start.countDown();

            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
            assertEquals(1, query.count());
        }
    }

    @Test
    void failedBatchClaimCanBeReacquired() {
        assertTrue(claimAdapter.claim(candidateBatch()));
        JdbcClient.create(dataSource).sql("""
                        update trading.candidate_batch_processing
                        set status = 'FAILED', failure_reason = 'temporary failure'
                        where batch_id = :batchId
                        """)
                .param("batchId", BATCH_ID)
                .update();

        assertTrue(claimAdapter.claim(candidateBatch()));
        assertEquals(CandidateBatchProcessingStatus.PROCESSING, query.findByBatchId(BATCH_ID).orElseThrow().status());
    }

    @Test
    void abandonedProcessingClaimCanBeReacquired() {
        assertTrue(claimAdapter.claim(candidateBatch()));
        JdbcClient.create(dataSource).sql("""
                        update trading.candidate_batch_processing
                        set updated_at = current_timestamp - interval '16 minutes'
                        where batch_id = :batchId
                        """)
                .param("batchId", BATCH_ID)
                .update();

        assertTrue(claimAdapter.claim(candidateBatch()));
        assertEquals(1, query.count());
    }

    private static CandidateBatch candidateBatch() {
        return new CandidateBatch(
                BATCH_ID,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of());
    }
}
