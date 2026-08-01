package com.idea2strategy.trading.application.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.candidate.CandidateOrder;
import com.idea2strategy.trading.domain.order.Order;
import com.idea2strategy.trading.domain.settlement.Settlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateBatchProcessorTest {

    @Test
    void deliveringSameBatchTwiceProcessesItOnce() {
        RecordingPorts ports = new RecordingPorts();
        CandidateBatchProcessor processor = ports.processor();
        CandidateBatch batch = candidateBatch();

        CandidateBatchProcessingResult first = processor.process(batch);
        CandidateBatchProcessingResult duplicate = processor.process(batch);

        assertEquals(CandidateBatchProcessingResult.PROCESSED, first);
        assertEquals(CandidateBatchProcessingResult.DUPLICATE, duplicate);
        assertEquals(1, ports.claimedBatchIds.size());
        assertEquals(1, ports.orders.size());
        assertEquals(1, ports.executions.size());
        assertEquals(1, ports.settlements.size());
        assertEquals(List.of(batch.batchId()), ports.completedBatchIds);
        assertEquals(List.of(), ports.failures);
    }

    @Test
    void downstreamFailureIsRecordedAndPropagated() {
        RecordingPorts ports = new RecordingPorts();
        IllegalStateException failure = new IllegalStateException("execution unavailable");
        ports.executionFailure = failure;
        CandidateBatch batch = candidateBatch();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ports.processor().process(batch));

        assertSame(failure, thrown);
        assertEquals(List.of(), ports.completedBatchIds);
        assertEquals(List.of(new Failure(batch.batchId(), "execution unavailable")), ports.failures);
    }

    private static CandidateBatch candidateBatch() {
        return new CandidateBatch(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new CandidateOrder(
                        UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        "BUY",
                        new BigDecimal("2"),
                        new BigDecimal("150.25"),
                        List.of("strategy-entry"))));
    }

    private record Failure(UUID batchId, String reason) {
    }

    private static final class RecordingPorts
            implements CandidateBatchClaimPort, CandidateBatchStatusPort, OrderPort, ExecutionPort, SettlementPort {
        private final Set<UUID> claimedBatchIds = new HashSet<>();
        private final List<UUID> completedBatchIds = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private final List<Order> orders = new ArrayList<>();
        private final List<Execution> executions = new ArrayList<>();
        private final List<Settlement> settlements = new ArrayList<>();
        private RuntimeException executionFailure;

        CandidateBatchProcessor processor() {
            return new CandidateBatchProcessor(this, this, this, this, this);
        }

        @Override
        public boolean claim(CandidateBatch batch) {
            return claimedBatchIds.add(batch.batchId());
        }

        @Override
        public void complete(UUID batchId) {
            completedBatchIds.add(batchId);
        }

        @Override
        public void fail(UUID batchId, String reason) {
            failures.add(new Failure(batchId, reason));
        }

        @Override
        public Order place(CandidateOrder candidate) {
            Order order = new Order(
                    UUID.fromString("50000000-0000-0000-0000-000000000005"),
                    candidate.candidateId(),
                    candidate.instrumentId(),
                    candidate.side(),
                    candidate.quantity(),
                    candidate.limitPrice());
            orders.add(order);
            return order;
        }

        @Override
        public Execution execute(Order order) {
            if (executionFailure != null) {
                throw executionFailure;
            }
            Execution execution = new Execution(
                    UUID.fromString("60000000-0000-0000-0000-000000000006"),
                    order.orderId(),
                    order.quantity(),
                    order.limitPrice());
            executions.add(execution);
            return execution;
        }

        @Override
        public Settlement settle(Execution execution) {
            Settlement settlement = new Settlement(
                    UUID.fromString("70000000-0000-0000-0000-000000000007"),
                    execution.executionId());
            settlements.add(settlement);
            return settlement;
        }
    }
}
