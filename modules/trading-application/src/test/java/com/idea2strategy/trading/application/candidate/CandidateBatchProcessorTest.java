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
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
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

    @Test
    void failureRecordingDoesNotMaskOriginalFailure() {
        RecordingPorts ports = new RecordingPorts();
        IllegalStateException executionFailure = new IllegalStateException("execution unavailable");
        IllegalStateException recordingFailure = new IllegalStateException("status store unavailable");
        ports.executionFailure = executionFailure;
        ports.failureRecordingFailure = recordingFailure;

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ports.processor().process(candidateBatch()));

        assertSame(executionFailure, thrown);
        assertEquals(List.of(recordingFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void failedBatchCanBeRetriedWithoutDuplicatingDownstreamEffects() {
        RecordingPorts ports = new RecordingPorts();
        ports.executionFailure = new IllegalStateException("temporary execution failure");
        CandidateBatch batch = candidateBatch();
        assertThrows(IllegalStateException.class, () -> ports.processor().process(batch));

        ports.executionFailure = null;
        CandidateBatchProcessingResult retried = ports.processor().process(batch);

        assertEquals(CandidateBatchProcessingResult.PROCESSED, retried);
        assertEquals(1, ports.orders.stream().map(Order::orderId).distinct().count());
        assertEquals(1, ports.executions.stream().map(Execution::executionId).distinct().count());
        assertEquals(1, ports.settlements.stream().map(Settlement::settlementId).distinct().count());
    }

    /**
     * BLOCK_NEW_WORK, seen from the intake side: a scoped batch for a bot whose stop settlement is
     * in flight is refused before anything is claimed, so a recovering settlement never races a
     * fresh claim for the same bot. An unscoped batch names no bot and passes untouched.
     */
    @Test
    void aScopedBatchOfAStoppingBotIsRefusedBeforeTheClaim() {
        RecordingPorts ports = new RecordingPorts();
        UUID botId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        ports.stopSettlementActive(com.idea2strategy.trading.domain.stop.BotStopSettlement.request(
                botId, com.idea2strategy.trading.domain.stop.StopReason.USER_REQUEST,
                "owner pressed stop", Instant.parse("2026-08-01T00:00:00Z")));
        CandidateBatch scoped = new CandidateBatch(
                UUID.fromString("11000000-0000-0000-0000-000000000001"),
                UUID.fromString("21000000-0000-0000-0000-000000000002"),
                botId,
                UUID.fromString("51000000-0000-0000-0000-000000000006"),
                UUID.fromString("52000000-0000-0000-0000-000000000007"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new CandidateOrder(
                        UUID.fromString("31000000-0000-0000-0000-000000000003"),
                        UUID.fromString("41000000-0000-0000-0000-000000000004"),
                        UUID.fromString("53000000-0000-0000-0000-000000000008"),
                        "BUY",
                        new BigDecimal("2"),
                        new BigDecimal("150.25"),
                        List.of("strategy-entry"))));

        CandidateBatchProcessingResult result = ports.processor().process(scoped);
        CandidateBatchProcessingResult unscoped = ports.processor().process(candidateBatch());

        assertEquals(CandidateBatchProcessingResult.BLOCKED_BY_STOP, result);
        assertEquals(CandidateBatchProcessingResult.PROCESSED, unscoped);
        assertEquals(List.of(), ports.orders.stream()
                .filter(order -> order.candidateId().equals(
                        UUID.fromString("31000000-0000-0000-0000-000000000003")))
                .toList());
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
            implements CandidateBatchClaimPort, CandidateBatchStatusPort, OrderPort, ExecutionPort,
                SettlementPort, com.idea2strategy.trading.application.port.BotStopSettlementStore {
        private final Set<UUID> claimedBatchIds = new HashSet<>();
        private final Set<UUID> failedBatchIds = new HashSet<>();
        private final Map<UUID, CandidateBatchClaim> activeClaims = new HashMap<>();
        private final List<UUID> completedBatchIds = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private final List<Order> orders = new ArrayList<>();
        private final List<Execution> executions = new ArrayList<>();
        private final List<Settlement> settlements = new ArrayList<>();
        private RuntimeException executionFailure;
        private RuntimeException failureRecordingFailure;

        CandidateBatchProcessor processor() {
            return new CandidateBatchProcessor(this, this, this, this, this, this);
        }

        /** A stoppable bot for the intake gate; every test here runs with no settlement active. */
        private com.idea2strategy.trading.domain.stop.BotStopSettlement activeSettlement;

        void stopSettlementActive(com.idea2strategy.trading.domain.stop.BotStopSettlement settlement) {
            this.activeSettlement = settlement;
        }

        @Override
        public com.idea2strategy.trading.domain.stop.BotStopSettlement createOrLoad(
                com.idea2strategy.trading.domain.stop.BotStopSettlement desired) {
            throw new UnsupportedOperationException("not part of candidate processing");
        }

        @Override
        public com.idea2strategy.trading.domain.stop.BotStopSettlement load(UUID settlementId) {
            throw new UnsupportedOperationException("not part of candidate processing");
        }

        @Override
        public Optional<com.idea2strategy.trading.domain.stop.BotStopSettlement> findActive(UUID botId) {
            return Optional.ofNullable(activeSettlement)
                    .filter(settlement -> settlement.botId().equals(botId));
        }

        @Override
        public List<com.idea2strategy.trading.domain.stop.BotStopSettlement> loadRecoverable() {
            throw new UnsupportedOperationException("not part of candidate processing");
        }

        @Override
        public com.idea2strategy.trading.domain.stop.BotStopSettlement recordStep(
                com.idea2strategy.trading.domain.stop.BotStopSettlement current,
                com.idea2strategy.trading.domain.stop.StopStep step,
                com.idea2strategy.trading.application.stop.StopStepResult result,
                java.time.Instant occurredAt) {
            throw new UnsupportedOperationException("not part of candidate processing");
        }

        @Override
        public Optional<CandidateBatchClaim> claim(CandidateBatch batch) {
            boolean acquired = claimedBatchIds.add(batch.batchId()) || failedBatchIds.remove(batch.batchId());
            if (!acquired) {
                return Optional.empty();
            }
            CandidateBatchClaim claim = new CandidateBatchClaim(batch.batchId(), UUID.randomUUID());
            activeClaims.put(batch.batchId(), claim);
            return Optional.of(claim);
        }

        @Override
        public boolean renew(CandidateBatchClaim claim) {
            return claim.equals(activeClaims.get(claim.batchId()));
        }

        @Override
        public void complete(CandidateBatchClaim claim) {
            completedBatchIds.add(claim.batchId());
            activeClaims.remove(claim.batchId());
        }

        @Override
        public void fail(CandidateBatchClaim claim, String reason) {
            failures.add(new Failure(claim.batchId(), reason));
            failedBatchIds.add(claim.batchId());
            activeClaims.remove(claim.batchId());
            if (failureRecordingFailure != null) {
                throw failureRecordingFailure;
            }
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
