package com.idea2strategy.trading.persistence.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.domain.execution.FillDecision;
import com.idea2strategy.trading.application.execution.FillDecisionConflictException;
import com.idea2strategy.trading.domain.execution.RealisticFillModel;
import com.idea2strategy.trading.domain.execution.RecordedMarketSnapshot;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

class VirtualFillPersistenceTest {
    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresFillDecisionStore store;

    @BeforeAll static void migrate() {
        String externalUrl = System.getenv("F08_POSTGRES_URL");
        String username = System.getenv().getOrDefault("F08_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("F08_POSTGRES_PASSWORD", "postgres");
        if (externalUrl == null || externalUrl.isBlank()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "PostgreSQL integration test requires F08_POSTGRES_URL or an available Docker daemon");
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            externalUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
        dataSource = new DriverManagerDataSource(externalUrl, username, password);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = newStore();
    }

    @AfterAll static void stopPostgres() { if (postgres != null) postgres.stop(); }

    @BeforeEach void clear() { jdbc.sql("truncate table trading.virtual_fill_decision").update(); }

    @Test void exactRetryAndRestartReturnOneDurableAuditedDecision() {
        FillDecision desired = decision();
        assertEquals(desired, store.createOrLoad(desired));
        assertEquals(desired, store.createOrLoad(desired));
        assertEquals(desired, newStore().createOrLoad(desired));
        assertEquals(desired, new JooqFillDecisionQuery(dataSource).findById(desired.decisionId()).orElseThrow());
        assertEquals(1, jdbc.sql("select count(*) from trading.virtual_fill_decision").query(Integer.class).single());
    }

    @Test void reusedIdentityWithChangedContentConflictsWithoutOverwrite() {
        FillDecision desired = decision();
        store.createOrLoad(desired);
        FillDecision changed = new FillDecision(desired.decisionId(), "changed", desired.orderId(),
                desired.orderVersion(), desired.snapshot(), desired.eligibility(), desired.value(), desired.evaluatedAt());
        assertThrows(FillDecisionConflictException.class, () -> store.createOrLoad(changed));
        assertEquals(desired, new JooqFillDecisionQuery(dataSource).findById(desired.decisionId()).orElseThrow());
    }

    private static PostgresFillDecisionStore newStore() {
        return new PostgresFillDecisionStore(JdbcClient.create(dataSource), new JdbcTransactionManager(dataSource));
    }

    private static FillDecision decision() {
        UUID instrument = UUID.fromString("30000000-0000-0000-0000-000000000003");
        Instant accepted = Instant.parse("2026-08-02T14:29:00Z");
        OrderLifecycle order = new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                UUID.fromString("20000000-0000-0000-0000-000000000003"), instrument,
                OrderSide.BUY, new BigDecimal("5"), OrderType.MARKET, TimeInForce.DAY,
                null, null, null, null), accepted);
        RecordedMarketSnapshot snapshot = new RecordedMarketSnapshot(
                UUID.fromString("40000000-0000-0000-0000-000000000003"), instrument, accepted.plusSeconds(60),
                new BigDecimal("99"), new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("2"), null);
        return new RealisticFillModel().evaluate(order, snapshot);
    }
}
