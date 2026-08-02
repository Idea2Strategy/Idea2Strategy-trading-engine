package com.idea2strategy.trading.persistence.fill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.application.fill.FillRecordConflictException;
import com.idea2strategy.trading.domain.fill.FillRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FillRecordPersistenceTest {
    private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private static PostgresFillRecordStore store;
    private static JooqFillRecordQuery query;

    @BeforeAll static void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqFillRecordQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach void clear() { jdbc.sql("truncate table trading.execution_fill_record cascade").update(); }

    @Test
    void eachPartialFillCountsOnceWhileCorrectionsRemainAppendOnly() {
        FillRecord first = original("execution-1", "0.25", "100.05", T0);
        FillRecord second = original("execution-2", "0.75", "100.06", T0.plusSeconds(1));
        store.appendOrLoad(first);
        store.appendOrLoad(second);
        FillRecord corrected = first.corrected(new BigDecimal("0.2"), new BigDecimal("100.04"),
                new BigDecimal("0.040016"), new BigDecimal("0.008"), T0.plusSeconds(2), T0.plusSeconds(3));
        store.appendOrLoad(corrected);

        assertEquals(2, query.tradeCount(ORDER_ID));
        assertEquals(List.of(first, second, corrected), query.history(ORDER_ID));
        assertEquals(corrected.fillRecordId(), query.effective(ORDER_ID, "execution-1").orElseThrow().fillRecordId());
        assertEquals(3, jdbc.sql("select count(*) from trading.execution_fill_record")
                .query(Integer.class).single());
    }

    @Test
    void exactRetrySurvivesRestartAndConflictingPayloadIsRejected() {
        FillRecord desired = original("execution-1", "0.25", "100.05", T0);
        assertEquals(desired, store.appendOrLoad(desired));
        assertEquals(desired, newStore().appendOrLoad(desired));
        FillRecord conflict = original("execution-1", "0.5", "100.05", T0);
        assertThrows(FillRecordConflictException.class, () -> newStore().appendOrLoad(conflict));
        assertEquals(1, query.tradeCount(ORDER_ID));
    }

    @Test
    void correctionCannotArriveBeforeItsOriginalOrSkipARevision() {
        FillRecord original = original("execution-1", "1", "10", T0);
        FillRecord correction = original.corrected(BigDecimal.ONE, new BigDecimal("9.9"),
                new BigDecimal("0.0198"), new BigDecimal("0.005"), T0.plusSeconds(1), T0.plusSeconds(1));
        assertThrows(FillRecordConflictException.class, () -> store.appendOrLoad(correction));
        store.appendOrLoad(original);
        FillRecord secondCorrection = correction.corrected(BigDecimal.ONE, new BigDecimal("9.8"),
                new BigDecimal("0.0196"), new BigDecimal("0.005"), T0.plusSeconds(2), T0.plusSeconds(2));
        assertThrows(FillRecordConflictException.class, () -> store.appendOrLoad(secondCorrection));
    }

    @Test
    void concurrentDuplicateDeliveryConvergesOnOneTrade() throws Exception {
        FillRecord desired = original("execution-race", "1", "10", T0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { ready.countDown(); start.await(); return newStore().appendOrLoad(desired); });
            var second = executor.submit(() -> { ready.countDown(); start.await(); return newStore().appendOrLoad(desired); });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(desired, first.get());
            assertEquals(desired, second.get());
        }
        assertEquals(1, query.tradeCount(ORDER_ID));
    }

    private static FillRecord original(String sourceId, String quantity, String price, Instant time) {
        BigDecimal q = new BigDecimal(quantity);
        BigDecimal p = new BigDecimal(price);
        return FillRecord.original(ORDER_ID, sourceId, q, p, q.multiply(p).multiply(new BigDecimal("0.002")),
                q.multiply(new BigDecimal("0.05")), time, time);
    }

    private static PostgresFillRecordStore newStore() {
        return new PostgresFillRecordStore(JdbcClient.create(dataSource), transactions);
    }
}
