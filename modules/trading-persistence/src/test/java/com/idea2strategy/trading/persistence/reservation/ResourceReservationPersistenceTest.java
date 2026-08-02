package com.idea2strategy.trading.persistence.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.reservation.ConsumeReservationCommand;
import com.idea2strategy.trading.application.reservation.ReleaseReservationCommand;
import com.idea2strategy.trading.application.reservation.ReservationConflictException;
import com.idea2strategy.trading.application.reservation.ReservationVersionConflictException;
import com.idea2strategy.trading.application.reservation.ResizeReservationCommand;
import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import com.idea2strategy.trading.domain.reservation.ResourceReservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
class ResourceReservationPersistenceTest {
    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static JdbcClient jdbc;
    private static DriverManagerDataSource dataSource;
    private static JdbcTransactionManager transactions;
    private static PostgresResourceReservationStore store;
    private static JooqResourceReservationQuery query;

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        store = newStore();
        query = new JooqResourceReservationQuery(DSL.using(dataSource, SQLDialect.POSTGRES));
    }

    @BeforeEach void clear() {
        jdbc.sql("truncate table trading.execution_resource_reservation cascade").update();
    }

    @Test
    void survivesRestartAndPersistsAnAuditableConservingLifecycle() {
        ResourceReservation initial = ResourceReservation.cash(UUID.randomUUID(), "USD", new BigDecimal("100"), T0);
        assertEquals(initial, store.createOrLoad(initial));
        assertEquals(initial, newStore().createOrLoad(initial));

        ConsumeReservationCommand consume = new ConsumeReservationCommand(UUID.randomUUID(), initial.reservationId(),
                1, new BigDecimal("35.25"), T0.plusSeconds(1));
        ResourceReservation partial = store.apply(consume);
        assertEquals(partial, newStore().apply(consume));
        ResourceReservation terminal = store.apply(new ReleaseReservationCommand(UUID.randomUUID(),
                initial.reservationId(), 2, T0.plusSeconds(2), "ORDER_CANCELLED"));

        assertEquals(ReservationStatus.SETTLED, terminal.status());
        assertEquals(0, new BigDecimal("100").compareTo(terminal.consumed().add(terminal.released())));
        assertEquals(terminal, query.findByReservationId(initial.reservationId()).orElseThrow().toDomain());
        assertEquals(List.of("RESERVE", "CONSUME", "RELEASE"), query.findMovements(initial.reservationId())
                .stream().map(JooqResourceReservationQuery.MovementView::type).toList());
    }

    @Test
    void positionReservationsConsumeLotsInFifoOrder() {
        UUID firstLot = UUID.randomUUID(); UUID secondLot = UUID.randomUUID();
        ResourceReservation initial = ResourceReservation.position(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("3"), List.of(
                        new LotReservationAllocation(secondLot, T0.plusSeconds(1), new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO),
                        new LotReservationAllocation(firstLot, T0, new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO)), T0.plusSeconds(2));
        ResourceReservation result = store.apply(new ConsumeReservationCommand(UUID.randomUUID(),
                store.createOrLoad(initial).reservationId(), 1, new BigDecimal("2.5"), T0.plusSeconds(3)));
        assertEquals(firstLot, result.lotAllocations().get(0).lotId());
        assertEquals(0, new BigDecimal("2").compareTo(result.lotAllocations().get(0).consumed()));
        assertEquals(0, new BigDecimal("0.5").compareTo(result.lotAllocations().get(1).consumed()));
    }

    @Test
    void rejectsVersionAndCommandIdentityConflictsWithoutChangingSnapshot() {
        ResourceReservation initial = store.createOrLoad(ResourceReservation.cash(
                UUID.randomUUID(), "USD", new BigDecimal("10"), T0));
        assertThrows(ReservationVersionConflictException.class, () -> store.apply(new ConsumeReservationCommand(
                UUID.randomUUID(), initial.reservationId(), 2, BigDecimal.ONE, T0.plusSeconds(1))));
        UUID commandId = UUID.randomUUID();
        store.apply(new ConsumeReservationCommand(commandId, initial.reservationId(), 1, BigDecimal.ONE, T0.plusSeconds(1)));
        assertThrows(ReservationConflictException.class, () -> store.apply(new ConsumeReservationCommand(
                commandId, initial.reservationId(), 1, new BigDecimal("2"), T0.plusSeconds(1))));
        assertEquals(new BigDecimal("1.000000000000000000"),
                query.findByReservationId(initial.reservationId()).orElseThrow().consumed());
    }

    @Test
    void resizingIsAtomicAndConcurrentWritersCannotOversubscribeOneReservation() throws Exception {
        ResourceReservation initial = store.createOrLoad(ResourceReservation.cash(
                UUID.randomUUID(), "USD", new BigDecimal("100"), T0));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        int successes = 0;
        int conflicts = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(resizeTask(initial, "130", ready, start));
            var second = executor.submit(resizeTask(initial, "70", ready, start));
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof ReservationVersionConflictException) conflicts++;
                    else throw exception;
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1, conflicts);
        ResourceReservation stored = query.findByReservationId(initial.reservationId()).orElseThrow().toDomain();
        assertEquals(2, stored.version());
        assertEquals(0, stored.remaining().compareTo(stored.reserved()));
        assertEquals(2, query.findMovements(initial.reservationId()).size());
    }

    private static Callable<ResourceReservation> resizeTask(
            ResourceReservation initial, String target, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return newStore().apply(new ResizeReservationCommand(UUID.randomUUID(), initial.reservationId(), 1,
                    new BigDecimal(target), List.of(), T0.plusSeconds(1)));
        };
    }

    private static PostgresResourceReservationStore newStore() {
        return new PostgresResourceReservationStore(JdbcClient.create(dataSource), transactions);
    }
}
