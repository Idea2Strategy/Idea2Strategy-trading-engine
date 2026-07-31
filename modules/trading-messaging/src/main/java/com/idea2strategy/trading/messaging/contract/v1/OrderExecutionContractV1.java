package com.idea2strategy.trading.messaging.contract.v1;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class OrderExecutionContractV1 {
    private static final DecimalValueV1 FEE_RATE = new DecimalValueV1("0.002");
    private static final DecimalValueV1 SLIPPAGE_RATE = new DecimalValueV1("0.0005");

    private OrderExecutionContractV1() {
    }

    public enum Side { BUY, SELL, SELL_SHORT, BUY_TO_COVER }

    public enum OrderType { MARKET, LIMIT, STOP, STOP_LIMIT, TRAILING_STOP }

    public enum TimeInForce { DAY, GTC, GTD }

    public enum QuantityMode { WHOLE_SHARES, FRACTIONAL_SHARES, NOTIONAL_AMOUNT }

    public enum IntentDecision { ACCEPTED, REDUCED, REJECTED }

    public record CostPolicy(String version, DecimalValueV1 feeRate, DecimalValueV1 slippageRate) {
        public CostPolicy {
            ContractValidationV1.requiredText(version, "version");
            ContractValidationV1.required(feeRate, "feeRate");
            ContractValidationV1.required(slippageRate, "slippageRate");
            if (!FEE_RATE.equals(feeRate)) {
                throw new IllegalArgumentException("feeRate must be 0.002");
            }
            if (!SLIPPAGE_RATE.equals(slippageRate)) {
                throw new IllegalArgumentException("slippageRate must be 0.0005");
            }
        }
    }

    public record Intent(
        UUID intentId,
        UUID candidateId,
        UUID instrumentId,
        Side side,
        OrderType orderType,
        TimeInForce timeInForce,
        Instant expiresAt,
        QuantityMode quantityMode,
        DecimalValueV1 requestedQuantity,
        DecimalValueV1 approvedQuantity,
        IntentDecision decision,
        String reasonCode,
        CostPolicy costPolicy
    ) {
        public Intent {
            ContractValidationV1.required(intentId, "intentId");
            ContractValidationV1.required(candidateId, "candidateId");
            ContractValidationV1.required(instrumentId, "instrumentId");
            ContractValidationV1.required(side, "side");
            ContractValidationV1.required(orderType, "orderType");
            ContractValidationV1.required(timeInForce, "timeInForce");
            validateExpiry(timeInForce, expiresAt);
            ContractValidationV1.required(quantityMode, "quantityMode");
            ContractValidationV1.required(requestedQuantity, "requestedQuantity");
            ContractValidationV1.required(approvedQuantity, "approvedQuantity");
            ContractValidationV1.required(decision, "decision");
            ContractValidationV1.required(costPolicy, "costPolicy");
            validateQuantityMode(side, orderType, timeInForce, quantityMode);
        }

        private static void validateExpiry(TimeInForce timeInForce, Instant expiresAt) {
            if (timeInForce == TimeInForce.GTD) {
                ContractValidationV1.utcInstant(expiresAt, "expiry");
            } else if (expiresAt != null) {
                throw new IllegalArgumentException("expiry is allowed only for GTD orders");
            }
        }

        private static void validateQuantityMode(
            Side side,
            OrderType orderType,
            TimeInForce timeInForce,
            QuantityMode quantityMode
        ) {
            if (quantityMode == QuantityMode.WHOLE_SHARES) {
                return;
            }
            if (side == Side.SELL_SHORT) {
                throw new IllegalArgumentException("short orders require whole shares");
            }
            if (side != Side.BUY || orderType != OrderType.MARKET || timeInForce != TimeInForce.DAY) {
                throw new IllegalArgumentException("fractional and notional orders require eligible long MARKET/DAY orders");
            }
        }
    }

    public record IntentBatch(UUID batchId, UUID botId, UUID evaluationId, List<Intent> intents) {
        public IntentBatch {
            ContractValidationV1.required(batchId, "batchId");
            ContractValidationV1.required(botId, "botId");
            ContractValidationV1.required(evaluationId, "evaluationId");
            intents = List.copyOf(ContractValidationV1.required(intents, "intents"));

            var intentIds = new HashSet<UUID>();
            for (Intent intent : intents) {
                ContractValidationV1.required(intent, "intent");
                if (!intentIds.add(intent.intentId())) {
                    throw new IllegalArgumentException("duplicate intentId");
                }
            }
        }
    }
}
