package com.idea2strategy.trading.domain.eligibility;

import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;

public record OrderEligibilityRequest(
        InstrumentFractionalPolicy instrumentPolicy,
        OrderPositionEffect positionEffect,
        OrderType orderType,
        TimeInForce timeInForce,
        QuantityMode quantityMode,
        BigDecimal requestedValue) {

    private static final int MAX_PRECISION = 38;
    private static final int MAX_SCALE = 18;

    public OrderEligibilityRequest {
        required(instrumentPolicy, "instrumentPolicy");
        required(positionEffect, "positionEffect");
        required(orderType, "orderType");
        required(timeInForce, "timeInForce");
        required(quantityMode, "quantityMode");
        required(requestedValue, "requestedValue");
        if (requestedValue.signum() <= 0) {
            throw new IllegalArgumentException("requestedValue must be positive");
        }
        validateSupportedDecimal(requestedValue);
    }

    private static void validateSupportedDecimal(BigDecimal value) {
        BigDecimal normalized;
        try {
            normalized = value.stripTrailingZeros();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("requestedValue has an unsupported decimal scale", exception);
        }
        int scale = Math.max(0, normalized.scale());
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (scale > MAX_SCALE || integerDigits > MAX_PRECISION - MAX_SCALE) {
            throw new IllegalArgumentException("requestedValue exceeds numeric(38,18)");
        }
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
