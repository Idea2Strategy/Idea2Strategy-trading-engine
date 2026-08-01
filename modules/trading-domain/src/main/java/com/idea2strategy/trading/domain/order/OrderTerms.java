package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderTerms(
        UUID intentId,
        UUID candidateId,
        UUID instrumentId,
        OrderSide side,
        BigDecimal quantity,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        BigDecimal trailPercent,
        Instant expiresAt) {

    public OrderTerms {
        intentId = required(intentId, "intentId");
        candidateId = required(candidateId, "candidateId");
        instrumentId = required(instrumentId, "instrumentId");
        side = required(side, "side");
        quantity = positive(quantity, "quantity");
        type = required(type, "type");
        timeInForce = required(timeInForce, "timeInForce");
        limitPrice = normalized(limitPrice, "limitPrice");
        stopPrice = normalized(stopPrice, "stopPrice");
        trailPercent = normalized(trailPercent, "trailPercent");

        validateType(type, limitPrice, stopPrice, trailPercent);
        validateTimeInForce(timeInForce, expiresAt);
    }

    private static void validateType(
            OrderType type, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailPercent) {
        switch (type) {
            case MARKET -> requireAbsent(limitPrice, stopPrice, trailPercent, "MARKET");
            case LIMIT -> {
                requirePresent(limitPrice, "limitPrice");
                requireAbsent(stopPrice, trailPercent, "LIMIT");
            }
            case STOP -> {
                requirePresent(stopPrice, "stopPrice");
                requireAbsent(limitPrice, trailPercent, "STOP");
            }
            case STOP_LIMIT -> {
                requirePresent(limitPrice, "limitPrice");
                requirePresent(stopPrice, "stopPrice");
                requireAbsent(trailPercent, "STOP_LIMIT");
            }
            case TRAILING_STOP -> {
                requirePresent(trailPercent, "trailPercent");
                if (trailPercent.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("trailPercent must be no greater than one");
                }
                requireAbsent(limitPrice, stopPrice, "TRAILING_STOP");
            }
        }
    }

    private static void validateTimeInForce(TimeInForce timeInForce, Instant expiresAt) {
        if (timeInForce == TimeInForce.GTD && expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required for GTD");
        }
        if (timeInForce != TimeInForce.GTD && expiresAt != null) {
            throw new IllegalArgumentException(timeInForce + " does not permit expiresAt");
        }
    }

    private static void requireAbsent(BigDecimal first, BigDecimal second, String type) {
        if (first != null || second != null) {
            throw new IllegalArgumentException(type + " does not permit those price or trail fields");
        }
    }

    private static void requireAbsent(BigDecimal value, String type) {
        if (value != null) {
            throw new IllegalArgumentException(type + " does not permit those price or trail fields");
        }
    }

    private static void requireAbsent(BigDecimal first, BigDecimal second, BigDecimal third, String type) {
        if (first != null || second != null || third != null) {
            throw new IllegalArgumentException(type + " does not permit those price or trail fields");
        }
    }

    private static void requirePresent(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = normalized(required(value, name), name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static BigDecimal normalized(BigDecimal value, String name) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
