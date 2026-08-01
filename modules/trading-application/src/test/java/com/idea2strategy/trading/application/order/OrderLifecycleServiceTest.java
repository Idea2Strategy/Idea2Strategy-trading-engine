package com.idea2strategy.trading.application.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.port.OrderLifecycleStore;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderLifecycleServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T00:01:00Z");

    @Test
    void rejectsNullConstructorDependencies() {
        RecordingStore store = new RecordingStore();

        assertThrows(IllegalArgumentException.class, () -> new OrderLifecycleService(null, store));
        assertThrows(IllegalArgumentException.class, () -> new OrderLifecycleService(new OrderLifecycleFactory(), null));
    }

    @Test
    void createsAcceptedLifecycleWithRealFactoryAndReturnsStoreResult() {
        OrderTerms terms = terms();
        OrderLifecycleFactory factory = new OrderLifecycleFactory();
        RecordingStore store = new RecordingStore();
        store.result = factory.accepted(terms, T0);
        OrderLifecycleService service = new OrderLifecycleService(factory, store);

        OrderLifecycle result = service.createAccepted(terms, T0);

        assertSame(store.result, result);
        assertEquals(factory.accepted(terms, T0), store.created);
        assertEquals(1, store.createCalls);
    }

    @Test
    void createsRejectedLifecycleWithRealFactoryAndReturnsStoreResult() {
        OrderTerms terms = terms();
        OrderLifecycleFactory factory = new OrderLifecycleFactory();
        RecordingStore store = new RecordingStore();
        store.result = factory.rejected(terms, T0, "RISK_REJECTED");
        OrderLifecycleService service = new OrderLifecycleService(factory, store);

        OrderLifecycle result = service.createRejected(terms, T0, "RISK_REJECTED");

        assertSame(store.result, result);
        assertEquals(factory.rejected(terms, T0, "RISK_REJECTED"), store.created);
        assertEquals(1, store.createCalls);
    }

    @Test
    void rejectsInvalidCreationInputsBeforeCallingStore() {
        RecordingStore store = new RecordingStore();
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);

        assertThrows(IllegalArgumentException.class, () -> service.createAccepted(null, T0));
        assertThrows(IllegalArgumentException.class, () -> service.createAccepted(terms(), null));
        assertThrows(IllegalArgumentException.class, () -> service.createRejected(terms(), T0, " "));

        assertEquals(0, store.createCalls);
    }

    @Test
    void rejectsNullStoreResults() {
        RecordingStore store = new RecordingStore();
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);

        assertThrows(IllegalArgumentException.class, () -> service.createAccepted(terms(), T0));

        assertEquals(1, store.createCalls);
    }

    @Test
    void forwardsFillCommandWithoutMutationAndReturnsStoreResult() {
        RecordingStore store = new RecordingStore();
        OrderLifecycle expected = new OrderLifecycleFactory().accepted(terms(), T0);
        store.result = expected;
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);
        FillOrderCommand command = new FillOrderCommand(UUID.randomUUID(), expected.orderId(), 1, BigDecimal.ONE, T1);

        OrderLifecycle result = service.applyFill(command);

        assertSame(expected, result);
        assertSame(command, store.command);
        assertEquals(1, store.applyCalls);
    }

    @Test
    void forwardsCancelAndExpireCommandsWithoutMutation() {
        RecordingStore store = new RecordingStore();
        OrderLifecycle expected = new OrderLifecycleFactory().accepted(terms(), T0);
        store.result = expected;
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);
        CancelOrderCommand cancel = new CancelOrderCommand(UUID.randomUUID(), expected.orderId(), 1, "USER_CANCELLED", T1);
        ExpireOrderCommand expire = new ExpireOrderCommand(UUID.randomUUID(), expected.orderId(), 1, T1, T0);

        assertSame(expected, service.cancel(cancel));
        assertSame(cancel, store.command);
        assertSame(expected, service.expire(expire));
        assertSame(expire, store.command);
        assertEquals(2, store.applyCalls);
    }

    @Test
    void rejectsNullCommandsBeforeCallingStore() {
        RecordingStore store = new RecordingStore();
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);

        assertThrows(IllegalArgumentException.class, () -> service.applyFill(null));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(null));
        assertThrows(IllegalArgumentException.class, () -> service.expire(null));

        assertEquals(0, store.applyCalls);
    }

    @Test
    void propagatesStoreExceptionsWithoutTranslation() {
        RecordingStore store = new RecordingStore();
        OrderLifecycleConflictException failure = new OrderLifecycleConflictException("already exists");
        store.failure = failure;
        OrderLifecycleService service = new OrderLifecycleService(new OrderLifecycleFactory(), store);

        OrderLifecycleConflictException thrown = assertThrows(
                OrderLifecycleConflictException.class,
                () -> service.createAccepted(terms(), T0));

        assertSame(failure, thrown);
    }

    @Test
    void commandRecordsRejectInvalidPublicInputs() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new FillOrderCommand(null, orderId, 1, BigDecimal.ONE, T1));
        assertThrows(IllegalArgumentException.class, () -> new FillOrderCommand(commandId, null, 1, BigDecimal.ONE, T1));
        assertThrows(IllegalArgumentException.class, () -> new FillOrderCommand(commandId, orderId, 0, BigDecimal.ONE, T1));
        assertThrows(IllegalArgumentException.class, () -> new FillOrderCommand(commandId, orderId, 1, BigDecimal.ZERO, T1));
        assertThrows(IllegalArgumentException.class, () -> new FillOrderCommand(commandId, orderId, 1, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> new CancelOrderCommand(commandId, orderId, 1, " ", T1));
        assertThrows(IllegalArgumentException.class, () -> new ExpireOrderCommand(commandId, orderId, 1, T1, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpireOrderCommand(commandId, orderId, 1, T0, T1));
    }

    private static OrderTerms terms() {
        return new OrderTerms(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                OrderSide.BUY,
                new BigDecimal("5"),
                OrderType.MARKET,
                TimeInForce.DAY,
                null,
                null,
                null,
                null);
    }

    private static final class RecordingStore implements OrderLifecycleStore {
        private OrderLifecycle result;
        private OrderLifecycle created;
        private OrderLifecycleCommand command;
        private RuntimeException failure;
        private int createCalls;
        private int applyCalls;

        @Override
        public OrderLifecycle createOrLoad(OrderLifecycle desired) {
            created = desired;
            createCalls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public OrderLifecycle apply(OrderLifecycleCommand requested) {
            command = requested;
            applyCalls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
