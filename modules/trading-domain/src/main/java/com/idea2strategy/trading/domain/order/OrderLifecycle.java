package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderLifecycle(
        UUID orderId,
        UUID createCommandId,
        String requestFingerprint,
        OrderTerms terms,
        OrderStatus status,
        BigDecimal cumulativeFilledQuantity,
        long version,
        Instant createdAt,
        Instant lastTransitionAt,
        String terminalReason) {

    public OrderLifecycle {
        orderId = required(orderId, "orderId");
        createCommandId = required(createCommandId, "createCommandId");
        requestFingerprint = required(requestFingerprint, "requestFingerprint");
        terms = required(terms, "terms");
        status = required(status, "status");
        cumulativeFilledQuantity = nonNegative(cumulativeFilledQuantity, "cumulativeFilledQuantity");
        if (cumulativeFilledQuantity.compareTo(terms.quantity()) > 0) {
            throw new IllegalArgumentException("cumulativeFilledQuantity must not exceed quantity");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        createdAt = required(createdAt, "createdAt");
        lastTransitionAt = required(lastTransitionAt, "lastTransitionAt");
        if (lastTransitionAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastTransitionAt must not precede createdAt");
        }
        if (terms.timeInForce() == TimeInForce.GTD && !terms.expiresAt().isAfter(createdAt)) {
            throw new IllegalArgumentException("GTD expiresAt must be after createdAt");
        }
        validateState(terms, status, cumulativeFilledQuantity, version, createdAt, lastTransitionAt, terminalReason);
        validateIdentity(orderId, createCommandId, requestFingerprint, terms, status, createdAt, terminalReason);
    }

    public OrderLifecycle applyFill(BigDecimal delta, Instant occurredAt) {
        requireOpen();
        BigDecimal normalizedDelta = positive(delta, "delta");
        Instant transitionAt = transitionTime(occurredAt);
        BigDecimal nextCumulative = cumulativeFilledQuantity.add(normalizedDelta).stripTrailingZeros();
        if (nextCumulative.compareTo(terms.quantity()) > 0) {
            throw new IllegalArgumentException("fill delta exceeds remaining quantity");
        }
        OrderStatus nextStatus = nextCumulative.compareTo(terms.quantity()) == 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
        return new OrderLifecycle(orderId, createCommandId, requestFingerprint, terms, nextStatus,
                nextCumulative, version + 1, createdAt, transitionAt, null);
    }

    public OrderLifecycle cancel(String reason, Instant occurredAt) {
        requireOpen();
        String cancellationReason = nonBlank(reason, "reason");
        return new OrderLifecycle(orderId, createCommandId, requestFingerprint, terms, OrderStatus.CANCELLED,
                cumulativeFilledQuantity, version + 1, createdAt, transitionTime(occurredAt), cancellationReason);
    }

    public OrderLifecycle expire(Instant now, Instant daySessionClose) {
        requireOpen();
        Instant occurredAt = transitionTime(now);
        return switch (terms.timeInForce()) {
            case DAY -> expireDay(occurredAt, daySessionClose);
            case GTD -> expireGtd(occurredAt);
            case GTC -> throw new IllegalStateException("GTC orders do not expire automatically");
        };
    }

    private OrderLifecycle expireDay(Instant now, Instant daySessionClose) {
        Instant close = required(daySessionClose, "daySessionClose");
        if (now.isBefore(close)) {
            throw new IllegalArgumentException("DAY order cannot expire before daySessionClose");
        }
        return expired(now, "DAY_SESSION_CLOSE");
    }

    private OrderLifecycle expireGtd(Instant now) {
        if (now.isBefore(terms.expiresAt())) {
            throw new IllegalArgumentException("GTD order cannot expire before expiresAt");
        }
        return expired(now, "GTD_EXPIRY");
    }

    private OrderLifecycle expired(Instant occurredAt, String reason) {
        return new OrderLifecycle(orderId, createCommandId, requestFingerprint, terms, OrderStatus.EXPIRED,
                cumulativeFilledQuantity, version + 1, createdAt, occurredAt, reason);
    }

    private void requireOpen() {
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException(status + " is terminal");
        }
    }

    private Instant transitionTime(Instant occurredAt) {
        Instant value = required(occurredAt, "occurredAt");
        if (value.isBefore(lastTransitionAt)) {
            throw new IllegalArgumentException("occurredAt must not precede lastTransitionAt");
        }
        return value;
    }

    private static void validateState(
            OrderTerms terms,
            OrderStatus status,
            BigDecimal cumulativeFilledQuantity,
            long version,
            Instant createdAt,
            Instant lastTransitionAt,
            String terminalReason) {
        switch (status) {
            case ACCEPTED -> {
                requireInitial(version, cumulativeFilledQuantity, createdAt, lastTransitionAt, status);
                requireNoReason(terminalReason, status);
            }
            case REJECTED -> {
                requireInitial(version, cumulativeFilledQuantity, createdAt, lastTransitionAt, status);
                nonBlank(terminalReason, "terminalReason");
            }
            case PARTIALLY_FILLED -> {
                if (version < 2 || cumulativeFilledQuantity.signum() <= 0
                        || cumulativeFilledQuantity.compareTo(terms.quantity()) >= 0) {
                    throw new IllegalArgumentException("PARTIALLY_FILLED state is inconsistent");
                }
                requireNoReason(terminalReason, status);
            }
            case FILLED -> {
                if (version < 2 || cumulativeFilledQuantity.compareTo(terms.quantity()) != 0) {
                    throw new IllegalArgumentException("FILLED state is inconsistent");
                }
                requireNoReason(terminalReason, status);
            }
            case CANCELLED -> {
                requireOpenTerminalShape(terms, status, cumulativeFilledQuantity, version);
                nonBlank(terminalReason, "terminalReason");
            }
            case EXPIRED -> {
                requireOpenTerminalShape(terms, status, cumulativeFilledQuantity, version);
                validateExpirationState(terms, lastTransitionAt, terminalReason);
            }
        }
    }

    private static void requireOpenTerminalShape(
            OrderTerms terms, OrderStatus status, BigDecimal cumulativeFilledQuantity, long version) {
        boolean isDirectTerminal = cumulativeFilledQuantity.signum() == 0 && version == 2;
        boolean followsPartialFill = cumulativeFilledQuantity.signum() > 0 && version >= 3;
        if ((!isDirectTerminal && !followsPartialFill)
                || cumulativeFilledQuantity.compareTo(terms.quantity()) >= 0) {
            throw new IllegalArgumentException(status + " state is inconsistent");
        }
    }

    private static void validateExpirationState(
            OrderTerms terms, Instant lastTransitionAt, String terminalReason) {
        switch (terms.timeInForce()) {
            case DAY -> requireExpirationReason(terminalReason, "DAY_SESSION_CLOSE");
            case GTD -> {
                requireExpirationReason(terminalReason, "GTD_EXPIRY");
                if (lastTransitionAt.isBefore(terms.expiresAt())) {
                    throw new IllegalArgumentException("GTD cannot be EXPIRED before expiresAt");
                }
            }
            case GTC -> throw new IllegalArgumentException("GTC cannot be EXPIRED");
        }
    }

    private static void requireExpirationReason(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("EXPIRED terminalReason must be " + expected);
        }
    }

    private static void requireInitial(
            long version,
            BigDecimal cumulativeFilledQuantity,
            Instant createdAt,
            Instant lastTransitionAt,
            OrderStatus status) {
        if (version != 1 || cumulativeFilledQuantity.signum() != 0 || !lastTransitionAt.equals(createdAt)) {
            throw new IllegalArgumentException(status + " is allowed only as an initial state");
        }
    }

    private static void validateIdentity(
            UUID orderId,
            UUID createCommandId,
            String requestFingerprint,
            OrderTerms terms,
            OrderStatus status,
            Instant createdAt,
            String terminalReason) {
        UUID expectedOrderId = OrderLifecycleIdentity.orderId(terms);
        if (!expectedOrderId.equals(orderId)) {
            throw new IllegalArgumentException("orderId does not match order terms");
        }
        UUID expectedCreateCommandId = OrderLifecycleIdentity.createCommandId(orderId);
        if (!expectedCreateCommandId.equals(createCommandId)) {
            throw new IllegalArgumentException("createCommandId does not match orderId");
        }
        OrderStatus initialStatus = status == OrderStatus.REJECTED ? OrderStatus.REJECTED : OrderStatus.ACCEPTED;
        String initialReason = status == OrderStatus.REJECTED ? terminalReason : null;
        String expectedFingerprint = OrderLifecycleIdentity.requestFingerprint(
                terms, initialStatus, createdAt, initialReason);
        if (!expectedFingerprint.equals(requestFingerprint)) {
            throw new IllegalArgumentException("requestFingerprint does not match initial aggregate");
        }
    }

    private static void requireNoReason(String value, OrderStatus status) {
        if (value != null) {
            throw new IllegalArgumentException(status + " does not permit terminalReason");
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
