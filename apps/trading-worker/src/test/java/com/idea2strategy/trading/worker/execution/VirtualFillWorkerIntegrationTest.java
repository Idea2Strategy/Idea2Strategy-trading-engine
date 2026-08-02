package com.idea2strategy.trading.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.application.execution.VirtualFillService;
import com.idea2strategy.trading.domain.execution.RecordedMarketSnapshot;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import com.idea2strategy.trading.persistence.execution.JooqFillDecisionQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
class VirtualFillWorkerIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired VirtualFillService service;
    @Autowired JooqFillDecisionQuery query;

    @Test
    void workerPersistsAndRecoversARealisticFillDecision() {
        UUID instrument = UUID.fromString("e3000000-0000-0000-0000-000000000003");
        Instant accepted = Instant.parse("2026-08-02T14:29:00Z");
        var order = new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("e1000000-0000-0000-0000-000000000001"),
                UUID.fromString("e2000000-0000-0000-0000-000000000002"), instrument,
                OrderSide.BUY, new BigDecimal("4"), OrderType.MARKET, TimeInForce.DAY,
                null, null, null, null), accepted);
        var snapshot = new RecordedMarketSnapshot(
                UUID.fromString("e4000000-0000-0000-0000-000000000004"), instrument, accepted.plusSeconds(60),
                new BigDecimal("99"), new BigDecimal("1.5"), new BigDecimal("100"), new BigDecimal("1.5"),
                new BigDecimal("100"), new BigDecimal("1.5"), null);

        var first = service.evaluate(order, snapshot);
        var retry = service.evaluate(order, snapshot);

        assertEquals(first, retry);
        assertEquals(first, query.findById(first.decisionId()).orElseThrow());
    }

}
