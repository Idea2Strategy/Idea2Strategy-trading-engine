package com.idea2strategy.trading.application.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.port.OrderIntentBatchStore;
import com.idea2strategy.trading.domain.intent.OrderIntentBatch;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchFactory;
import com.idea2strategy.trading.domain.intent.OrderIntentBatchRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderIntentBatchServiceTest {

    @Test
    void rejectsNullFactory() {
        assertThrows(NullPointerException.class, () -> new OrderIntentBatchService(null, desired -> desired));
    }

    @Test
    void rejectsNullStore() {
        assertThrows(NullPointerException.class, () -> new OrderIntentBatchService(new OrderIntentBatchFactory(), null));
    }

    @Test
    void rejectsNullRequestBeforeCallingTheFactoryOrStore() {
        RecordingStore store = new RecordingStore(null);

        assertThrows(
                NullPointerException.class,
                () -> new OrderIntentBatchService(new OrderIntentBatchFactory(), store).createOrLoad(null));

        assertEquals(0, store.calls);
    }

    @Test
    void rejectsNullStoreResult() {
        assertThrows(
                NullPointerException.class,
                () -> new OrderIntentBatchService(new OrderIntentBatchFactory(), desired -> null).createOrLoad(request()));
    }

    @Test
    void forwardsTheExactSingleFactoryOutputToStoreAndReturnsPersistedInstance() {
        OrderIntentBatchRequest request = request();
        OrderIntentBatch desired = new OrderIntentBatchFactory().create(request);
        OrderIntentBatch persisted = copyOf(desired);
        RecordingStore store = new RecordingStore(persisted);
        RecordingFactory factory = new RecordingFactory(desired);

        OrderIntentBatch result = new OrderIntentBatchService(factory, store)
                .createOrLoad(request);

        assertSame(persisted, result);
        assertSame(desired, store.received);
        assertSame(request, factory.received);
        assertEquals(1, factory.calls);
        assertEquals(1, store.calls);
    }

    @Test
    void propagatesStoreConflictWithoutRetryingOrReplacingIdentity() {
        OrderIntentBatchRequest request = request();
        OrderIntentBatch desired = new OrderIntentBatchFactory().create(request);
        OrderIntentBatchConflictException conflict = new OrderIntentBatchConflictException("existing batch differs");
        FailingStore store = new FailingStore(conflict);

        OrderIntentBatchConflictException thrown = assertThrows(
                OrderIntentBatchConflictException.class,
                () -> new OrderIntentBatchService(new OrderIntentBatchFactory(), store).createOrLoad(request));

        assertSame(conflict, thrown);
        assertEquals(desired, store.received);
        assertEquals(1, store.calls);
    }

    private static OrderIntentBatchRequest request() {
        return new OrderIntentBatchRequest(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                List.of(
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        UUID.fromString("50000000-0000-0000-0000-000000000005")));
    }

    private static OrderIntentBatch copyOf(OrderIntentBatch batch) {
        return new OrderIntentBatch(
                batch.batchId(),
                batch.botId(),
                batch.evaluationId(),
                batch.sourceCandidateBatchId(),
                batch.requestFingerprint(),
                batch.intents());
    }

    private static final class RecordingStore implements OrderIntentBatchStore {
        private final OrderIntentBatch persisted;
        private OrderIntentBatch received;
        private int calls;

        private RecordingStore(OrderIntentBatch persisted) {
            this.persisted = persisted;
        }

        @Override
        public OrderIntentBatch createOrLoad(OrderIntentBatch desired) {
            received = desired;
            calls++;
            return persisted;
        }
    }

    private static final class FailingStore implements OrderIntentBatchStore {
        private final OrderIntentBatchConflictException conflict;
        private OrderIntentBatch received;
        private int calls;

        private FailingStore(OrderIntentBatchConflictException conflict) {
            this.conflict = conflict;
        }

        @Override
        public OrderIntentBatch createOrLoad(OrderIntentBatch desired) {
            received = desired;
            calls++;
            throw conflict;
        }
    }

    private static final class RecordingFactory extends OrderIntentBatchFactory {
        private final OrderIntentBatch output;
        private OrderIntentBatchRequest received;
        private int calls;

        private RecordingFactory(OrderIntentBatch output) {
            this.output = output;
        }

        @Override
        public OrderIntentBatch create(OrderIntentBatchRequest request) {
            received = request;
            calls++;
            return output;
        }
    }
}
