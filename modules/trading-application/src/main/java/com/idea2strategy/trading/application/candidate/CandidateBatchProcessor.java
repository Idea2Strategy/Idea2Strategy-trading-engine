package com.idea2strategy.trading.application.candidate;

import com.idea2strategy.trading.application.port.CandidateBatchClaimPort;
import com.idea2strategy.trading.application.port.CandidateBatchStatusPort;
import com.idea2strategy.trading.application.port.ExecutionPort;
import com.idea2strategy.trading.application.port.OrderPort;
import com.idea2strategy.trading.application.port.SettlementPort;
import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.domain.execution.Execution;
import com.idea2strategy.trading.domain.order.Order;
import java.util.Objects;

public final class CandidateBatchProcessor {
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final CandidateBatchClaimPort claimPort;
    private final CandidateBatchStatusPort statusPort;
    private final OrderPort orderPort;
    private final ExecutionPort executionPort;
    private final SettlementPort settlementPort;

    public CandidateBatchProcessor(
            CandidateBatchClaimPort claimPort,
            CandidateBatchStatusPort statusPort,
            OrderPort orderPort,
            ExecutionPort executionPort,
            SettlementPort settlementPort) {
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort");
        this.statusPort = Objects.requireNonNull(statusPort, "statusPort");
        this.orderPort = Objects.requireNonNull(orderPort, "orderPort");
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort");
        this.settlementPort = Objects.requireNonNull(settlementPort, "settlementPort");
    }

    public CandidateBatchProcessingResult process(CandidateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!claimPort.claim(batch)) {
            return CandidateBatchProcessingResult.DUPLICATE;
        }

        try {
            batch.candidates().forEach(candidate -> {
                Order order = orderPort.place(candidate);
                Execution execution = executionPort.execute(order);
                settlementPort.settle(execution);
            });
            statusPort.complete(batch.batchId());
            return CandidateBatchProcessingResult.PROCESSED;
        } catch (RuntimeException failure) {
            try {
                statusPort.fail(batch.batchId(), boundedReason(failure));
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            throw failure;
        }
    }

    private static String boundedReason(RuntimeException failure) {
        String reason = failure.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = failure.getClass().getSimpleName();
        }
        return reason.substring(0, Math.min(reason.length(), MAX_FAILURE_REASON_LENGTH));
    }
}
