package com.idea2strategy.trading.application.candidate;

import com.idea2strategy.trading.application.port.BotStopSettlementStore;
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
    private final ScopedCandidateComposer composer;
    private final OrderPort orderPort;
    private final ExecutionPort executionPort;
    private final SettlementPort settlementPort;
    private final BotStopSettlementStore stopSettlements;

    /**
     * The fixture pipeline ports may be null in production: a version 1 batch carries no partition
     * scope, can never become a canonical intent, and only exists where the fake candidate source
     * is enabled. A scoped batch never touches them — it goes to the composer.
     */
    public CandidateBatchProcessor(
            CandidateBatchClaimPort claimPort,
            CandidateBatchStatusPort statusPort,
            ScopedCandidateComposer composer,
            OrderPort orderPort,
            ExecutionPort executionPort,
            SettlementPort settlementPort,
            BotStopSettlementStore stopSettlements) {
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort");
        this.statusPort = Objects.requireNonNull(statusPort, "statusPort");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.orderPort = orderPort;
        this.executionPort = executionPort;
        this.settlementPort = settlementPort;
        this.stopSettlements = Objects.requireNonNull(stopSettlements, "stopSettlements");
    }

    public CandidateBatchProcessingResult process(CandidateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        // BLOCK_NEW_WORK, enforced where new work enters. A bot whose stop settlement is still in
        // flight takes no new candidates, and refusing before the claim leaves no processing row a
        // recovering settlement would race against. An unscoped batch names no bot and cannot reach
        // a canonical order anyway, so only the scoped shape is gated.
        if (batch.carriesPartitionScope()
                && stopSettlements.findActive(batch.botId()).isPresent()) {
            return CandidateBatchProcessingResult.BLOCKED_BY_STOP;
        }
        CandidateBatchClaim claim = claimPort.claim(batch).orElse(null);
        if (claim == null) {
            return CandidateBatchProcessingResult.DUPLICATE;
        }

        try {
            if (batch.carriesPartitionScope()) {
                renewOrThrow(claim);
                composer.compose(batch);
            } else {
                if (orderPort == null || executionPort == null || settlementPort == null) {
                    throw new IllegalStateException(
                            "an unscoped batch is fixture-only and needs the fake pipeline ports");
                }
                batch.candidates().forEach(candidate -> {
                    renewOrThrow(claim);
                    Order order = orderPort.place(candidate);
                    Execution execution = executionPort.execute(order);
                    settlementPort.settle(execution);
                });
            }
            renewOrThrow(claim);
            statusPort.complete(claim);
            return CandidateBatchProcessingResult.PROCESSED;
        } catch (RuntimeException failure) {
            try {
                statusPort.fail(claim, boundedReason(failure));
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            throw failure;
        }
    }

    private void renewOrThrow(CandidateBatchClaim claim) {
        if (!claimPort.renew(claim)) {
            throw new CandidateBatchClaimLostException(claim);
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
