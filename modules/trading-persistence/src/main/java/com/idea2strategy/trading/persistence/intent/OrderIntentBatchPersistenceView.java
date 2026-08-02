package com.idea2strategy.trading.persistence.intent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the canonical intent tables actually hold, read back column for column.
 *
 * <p>Values stay in their stored form rather than being mapped into the domain. The point of this
 * projection is to check that the write landed in the canonical shape, and translating on the way
 * out would hide exactly the mistakes it exists to catch.
 */
public record OrderIntentBatchPersistenceView(
        UUID batchId,
        UUID botId,
        UUID partitionId,
        UUID sourceEventId,
        String status,
        String conflictPolicyHash,
        String compositionRulesVersion,
        String inputStateHash,
        String resultHash,
        Instant finalizedAt,
        List<IntentRow> intents) {

    public OrderIntentBatchPersistenceView {
        intents = List.copyOf(intents);
    }

    public record IntentRow(
            UUID intentId,
            String intentKey,
            UUID batchId,
            UUID botId,
            UUID partitionId,
            UUID sourceEventId,
            UUID evaluationRunId,
            UUID flowId,
            UUID instrumentId,
            String originType,
            String side,
            String positionEffect,
            String orderType,
            String timeInForce,
            BigDecimal requestedQuantity,
            BigDecimal postNettingQuantity,
            BigDecimal finalQuantity,
            BigDecimal limitPrice,
            String decision,
            String decisionReasonCode) {
    }
}
