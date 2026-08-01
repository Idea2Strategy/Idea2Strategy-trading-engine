package com.idea2strategy.trading.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.candidate.CandidateBatchClaimLostException;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.persistence.candidate.CandidateBatchProcessingStatus;
import com.idea2strategy.trading.persistence.candidate.JooqCandidateBatchQuery;
import com.idea2strategy.trading.persistence.candidate.PostgresCandidateBatchClaimAdapter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "trading.fake-candidate.enabled=true")
class TradingWorkerApplicationTest {
    private static final String FAKE_BATCH_ID = "81000000-0000-0000-0000-000000000001";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JooqCandidateBatchQuery query;

    @Autowired
    private PostgresCandidateBatchClaimAdapter claimAdapter;

    @Autowired
    private CandidateBatchStatusPort statusPort;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void startsIndependentlyAndConsumesFakeCandidateBatch() {
        var processing = query.findByBatchId(java.util.UUID.fromString(FAKE_BATCH_ID)).orElseThrow();

        assertEquals(CandidateBatchProcessingStatus.COMPLETED, processing.status());
    }

    @Test
    void recordsFailedBatchThroughJpaStatusAdapter() {
        UUID batchId = UUID.fromString("91000000-0000-0000-0000-000000000001");
        CandidateBatch batch = new CandidateBatch(
                batchId,
                UUID.fromString("92000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:01:00Z"),
                List.of());

        var claim = claimAdapter.claim(batch).orElseThrow();
        statusPort.fail(claim, "simulated downstream failure");

        var processing = query.findByBatchId(batchId).orElseThrow();
        assertEquals(CandidateBatchProcessingStatus.FAILED, processing.status());
        assertEquals("simulated downstream failure", processing.failureReason());
    }

    @Test
    void reclaimedBatchRejectsCompletionFromPreviousClaimant() {
        UUID batchId = UUID.fromString("93000000-0000-0000-0000-000000000003");
        CandidateBatch batch = new CandidateBatch(
                batchId,
                UUID.fromString("94000000-0000-0000-0000-000000000004"),
                Instant.parse("2026-08-01T00:02:00Z"),
                List.of());
        var previous = claimAdapter.claim(batch).orElseThrow();
        jdbcClient.sql("""
                        update trading.candidate_batch_processing
                        set lease_expires_at = current_timestamp - interval '1 minute'
                        where batch_id = :batchId
                        """)
                .param("batchId", batchId)
                .update();
        var current = claimAdapter.claim(batch).orElseThrow();

        assertFalse(claimAdapter.renew(previous));
        assertThrows(CandidateBatchClaimLostException.class, () -> statusPort.complete(previous));
        statusPort.complete(current);
        assertEquals(CandidateBatchProcessingStatus.COMPLETED, query.findByBatchId(batchId).orElseThrow().status());
    }
}
