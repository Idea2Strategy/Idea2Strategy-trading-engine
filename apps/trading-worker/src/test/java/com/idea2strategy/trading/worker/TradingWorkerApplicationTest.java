package com.idea2strategy.trading.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.persistence.candidate.CandidateBatchProcessingStatus;
import com.idea2strategy.trading.persistence.candidate.JooqCandidateBatchQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void startsIndependentlyAndConsumesFakeCandidateBatch() {
        var processing = query.findByBatchId(java.util.UUID.fromString(FAKE_BATCH_ID)).orElseThrow();

        assertEquals(CandidateBatchProcessingStatus.COMPLETED, processing.status());
    }
}
