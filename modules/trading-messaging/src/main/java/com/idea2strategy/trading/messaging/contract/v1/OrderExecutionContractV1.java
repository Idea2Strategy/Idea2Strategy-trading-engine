package com.idea2strategy.trading.messaging.contract.v1;

import java.math.BigDecimal;
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

    public record OrderParameters(
        CurrencyAmountV1 limitPrice,
        CurrencyAmountV1 stopPrice,
        DecimalValueV1 trailPercent
    ) {
        public OrderParameters {
            if (limitPrice != null) {
                ContractValidationV1.positiveDecimal(limitPrice.amount(), "limitPrice");
            }
            if (stopPrice != null) {
                ContractValidationV1.positiveDecimal(stopPrice.amount(), "stopPrice");
            }
            if (trailPercent != null) {
                ContractValidationV1.positiveDecimal(trailPercent, "trailPercent");
                if (trailPercent.asBigDecimal().compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("trailPercent must be at most 1");
                }
            }
        }

        public void validateFor(OrderType orderType) {
            ContractValidationV1.required(orderType, "orderType");
            boolean valid = switch (orderType) {
                case MARKET -> limitPrice == null && stopPrice == null && trailPercent == null;
                case LIMIT -> limitPrice != null && stopPrice == null && trailPercent == null;
                case STOP -> limitPrice == null && stopPrice != null && trailPercent == null;
                case STOP_LIMIT -> limitPrice != null && stopPrice != null && trailPercent == null;
                case TRAILING_STOP -> limitPrice == null && stopPrice == null && trailPercent != null;
            };
            if (!valid) {
                throw new IllegalArgumentException(switch (orderType) {
                    case MARKET -> "MARKET orders do not allow limitPrice, stopPrice, or trailPercent";
                    case LIMIT -> "LIMIT orders require only limitPrice";
                    case STOP -> "STOP orders require only stopPrice";
                    case STOP_LIMIT -> "STOP_LIMIT orders require limitPrice and stopPrice only";
                    case TRAILING_STOP -> "TRAILING_STOP orders require only trailPercent";
                });
            }
        }
    }

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
        OrderParameters orderParameters,
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
            ContractValidationV1.required(orderParameters, "orderParameters").validateFor(orderType);
            ContractValidationV1.required(timeInForce, "timeInForce");
            validateExpiry(timeInForce, expiresAt);
            ContractValidationV1.required(quantityMode, "quantityMode");
            ContractValidationV1.required(requestedQuantity, "requestedQuantity");
            ContractValidationV1.required(approvedQuantity, "approvedQuantity");
            ContractValidationV1.required(decision, "decision");
            ContractValidationV1.required(costPolicy, "costPolicy");
            validateQuantityMode(side, orderType, timeInForce, quantityMode);
            validateWholeShareQuantities(quantityMode, requestedQuantity, approvedQuantity);
            validateDecision(decision, requestedQuantity, approvedQuantity, reasonCode);
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

        private static void validateWholeShareQuantities(
            QuantityMode quantityMode,
            DecimalValueV1 requestedQuantity,
            DecimalValueV1 approvedQuantity
        ) {
            if (quantityMode == QuantityMode.WHOLE_SHARES
                && (!isWholeNumber(requestedQuantity) || !isWholeNumber(approvedQuantity))) {
                throw new IllegalArgumentException("whole shares require mathematically integral quantities");
            }
        }

        private static boolean isWholeNumber(DecimalValueV1 quantity) {
            return quantity.asBigDecimal().stripTrailingZeros().scale() <= 0;
        }

        private static void validateDecision(
            IntentDecision decision,
            DecimalValueV1 requestedQuantity,
            DecimalValueV1 approvedQuantity,
            String reasonCode
        ) {
            var requested = requestedQuantity.asBigDecimal();
            var approved = approvedQuantity.asBigDecimal();
            ContractValidationV1.positiveDecimal(requestedQuantity, "requestedQuantity");
            switch (decision) {
                case ACCEPTED -> {
                    ContractValidationV1.positiveDecimal(approvedQuantity, "approvedQuantity");
                    if (approved.compareTo(requested) != 0) {
                        throw new IllegalArgumentException("ACCEPTED intents must approve the requested quantity");
                    }
                    if (reasonCode != null) {
                        throw new IllegalArgumentException("reasonCode is allowed only for REDUCED and REJECTED intents");
                    }
                }
                case REDUCED -> {
                    ContractValidationV1.positiveDecimal(approvedQuantity, "approvedQuantity");
                    if (approved.compareTo(BigDecimal.ZERO) <= 0 || approved.compareTo(requested) >= 0) {
                        throw new IllegalArgumentException("REDUCED intents must approve a positive quantity below the requested quantity");
                    }
                    ContractValidationV1.requiredText(reasonCode, "reasonCode");
                }
                case REJECTED -> {
                    if (approved.compareTo(BigDecimal.ZERO) != 0) {
                        throw new IllegalArgumentException("REJECTED intents must approve zero quantity");
                    }
                    ContractValidationV1.requiredText(reasonCode, "reasonCode");
                }
            }
        }
    }

    public record IntentBatch(UUID batchId, UUID botId, UUID evaluationId, List<Intent> intents) {
        public IntentBatch {
            ContractValidationV1.required(batchId, "batchId");
            ContractValidationV1.required(botId, "botId");
            ContractValidationV1.required(evaluationId, "evaluationId");
            intents = ContractValidationV1.required(intents, "intents");
            for (Intent intent : intents) {
                ContractValidationV1.required(intent, "intent");
            }
            intents = List.copyOf(intents);

            var intentIds = new HashSet<UUID>();
            for (Intent intent : intents) {
                if (!intentIds.add(intent.intentId())) {
                    throw new IllegalArgumentException("duplicate intentId");
                }
            }
        }
    }
}
