package com.idea2strategy.trading.domain.intent;

import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One candidate as the bundle decided it, before identity is derived.
 *
 * <p>Everything the canonical {@code trading.order_intents} row needs that a candidate alone cannot
 * supply arrives here: the position effect the eligibility check resolved, the order contract, and
 * the decision with its reason. Validation mirrors the canonical CHECKs so an invalid intent is
 * refused in the domain rather than by the database, which keeps the failure readable.
 */
public record OrderIntentRequest(
        UUID candidateId,
        UUID flowId,
        UUID instrumentId,
        OrderSide side,
        OrderPositionEffect positionEffect,
        OrderType orderType,
        TimeInForce timeInForce,
        BigDecimal requestedQuantity,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        Instant requestedExpiresAt,
        IntentDecision decision,
        String decisionReasonCode,
        BigDecimal finalQuantity) {

    private static final int MAX_REASON_CODE_LENGTH = 80;

    /** Every canonical amount on {@code trading.order_intents} is {@code numeric(_,8)}. */
    private static final int CANONICAL_SCALE = 8;

    public OrderIntentRequest {
        OrderIntentIdentityHashing.requireNonNull(candidateId, "candidateId");
        OrderIntentIdentityHashing.requireNonNull(flowId, "flowId");
        OrderIntentIdentityHashing.requireNonNull(instrumentId, "instrumentId");
        OrderIntentIdentityHashing.requireNonNull(side, "side");
        OrderIntentIdentityHashing.requireNonNull(positionEffect, "positionEffect");
        OrderIntentIdentityHashing.requireNonNull(orderType, "orderType");
        OrderIntentIdentityHashing.requireNonNull(timeInForce, "timeInForce");
        OrderIntentIdentityHashing.requireNonNull(requestedQuantity, "requestedQuantity");
        OrderIntentIdentityHashing.requireNonNull(decision, "decision");
        OrderIntentIdentityHashing.requireNonNull(decisionReasonCode, "decisionReasonCode");

        if (requestedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("requestedQuantity must be positive");
        }

        if (decisionReasonCode.isBlank() || decisionReasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "decisionReasonCode must be 1 to " + MAX_REASON_CODE_LENGTH + " characters");
        }
        requireSideMatchesEffect(side, positionEffect);
        requireOrderContract(orderType, timeInForce, limitPrice, stopPrice, requestedExpiresAt);
        requireFinalQuantityMatchesDecision(decision, finalQuantity, requestedQuantity);

        // Pinned to the canonical scale last, once the values are known to be valid. This record is
        // compared by value to decide whether a redelivery carried identical work, and PostgreSQL
        // always hands the amount back at scale 8; without this, 1.5 written and 1.50000000 read
        // would look like different work.
        requestedQuantity = atCanonicalScale(requestedQuantity, "requestedQuantity");
        finalQuantity = atCanonicalScale(finalQuantity, "finalQuantity");
        limitPrice = atCanonicalScale(limitPrice, "limitPrice");
        stopPrice = atCanonicalScale(stopPrice, "stopPrice");
    }

    /**
     * Mirrors the canonical {@code intent_side_effect_compatible} CHECK. A buy either opens a long
     * or closes a short; it can never reduce a long.
     */
    private static void requireSideMatchesEffect(OrderSide side, OrderPositionEffect effect) {
        boolean compatible = switch (side) {
            case BUY -> effect == OrderPositionEffect.INCREASE_LONG
                    || effect == OrderPositionEffect.REDUCE_SHORT;
            case SELL -> effect == OrderPositionEffect.REDUCE_LONG
                    || effect == OrderPositionEffect.INCREASE_SHORT;
        };
        if (!compatible) {
            throw new IllegalArgumentException(side + " is not compatible with " + effect);
        }
    }

    private static void requireOrderContract(
            OrderType orderType,
            TimeInForce timeInForce,
            BigDecimal limitPrice,
            BigDecimal stopPrice,
            Instant requestedExpiresAt) {
        switch (orderType) {
            case MARKET -> {
                // Canonical intent_market_contract_valid: a market intent carries no price and is DAY.
                if (limitPrice != null || stopPrice != null || timeInForce != TimeInForce.DAY) {
                    throw new IllegalArgumentException("a MARKET intent must be DAY with no price");
                }
            }
            case LIMIT -> require(limitPrice != null, "a LIMIT intent requires limitPrice");
            case STOP -> require(stopPrice != null, "a STOP intent requires stopPrice");
            case STOP_LIMIT -> require(
                    limitPrice != null && stopPrice != null,
                    "a STOP_LIMIT intent requires limitPrice and stopPrice");
            case TRAILING_STOP -> throw new IllegalArgumentException(
                    "TRAILING_STOP requires a trailing offset this request does not carry");
        }
        if (timeInForce == TimeInForce.GTD) {
            require(requestedExpiresAt != null, "a GTD intent requires requestedExpiresAt");
        } else {
            require(requestedExpiresAt == null, "only a GTD intent carries requestedExpiresAt");
        }
    }

    /**
     * Mirrors the canonical {@code intent_nonexecuting_decision_has_no_final} CHECK, and refuses a
     * reduction that is not actually smaller than what was asked for.
     */
    private static void requireFinalQuantityMatchesDecision(
            IntentDecision decision, BigDecimal finalQuantity, BigDecimal requestedQuantity) {
        if (!decision.executes()) {
            require(
                    finalQuantity == null || finalQuantity.signum() == 0,
                    decision + " must not carry a final quantity");
            return;
        }
        require(finalQuantity != null, decision + " requires a final quantity");
        require(finalQuantity.signum() > 0, decision + " requires a positive final quantity");
        int comparedToRequested = finalQuantity.compareTo(requestedQuantity);
        if (decision == IntentDecision.APPROVED) {
            require(comparedToRequested == 0, "APPROVED must keep the requested quantity");
        } else {
            require(comparedToRequested < 0, "REDUCED must be smaller than the requested quantity");
        }
    }

    /** Refuses rather than rounds: a quantity the canonical column cannot hold is a caller mistake. */
    private static BigDecimal atCanonicalScale(BigDecimal value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(CANONICAL_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    name + " exceeds the canonical scale of " + CANONICAL_SCALE, notExactlyRepresentable);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
