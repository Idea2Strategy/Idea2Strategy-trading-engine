package com.idea2strategy.trading.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    public OrderLifecycle {
        orderId = version5(orderId, "orderId");
        createCommandId = version5(createCommandId, "createCommandId");
        requestFingerprint = required(requestFingerprint, "requestFingerprint");
        if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("requestFingerprint must be lowercase SHA-256 hex");
        }
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
        validateState(terms, status, cumulativeFilledQuantity, version, terminalReason);
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
            OrderTerms terms, OrderStatus status, BigDecimal cumulativeFilledQuantity, long version, String terminalReason) {
        switch (status) {
            case ACCEPTED -> {
                requireInitial(version, cumulativeFilledQuantity, status);
                requireNoReason(terminalReason, status);
            }
            case REJECTED -> {
                requireInitial(version, cumulativeFilledQuantity, status);
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
            case CANCELLED, EXPIRED -> {
                if (version < 2 || cumulativeFilledQuantity.compareTo(terms.quantity()) >= 0) {
                    throw new IllegalArgumentException(status + " state is inconsistent");
                }
                nonBlank(terminalReason, "terminalReason");
            }
        }
    }

    private static void requireInitial(long version, BigDecimal cumulativeFilledQuantity, OrderStatus status) {
        if (version != 1 || cumulativeFilledQuantity.signum() != 0) {
            throw new IllegalArgumentException(status + " is allowed only as an initial state");
        }
    }

    private static void requireNoReason(String value, OrderStatus status) {
        if (value != null) {
            throw new IllegalArgumentException(status + " does not permit terminalReason");
        }
    }

    private static UUID version5(UUID value, String name) {
        required(value, name);
        if (value.version() != 5 || value.variant() != 2) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 version 5 UUID");
        }
        return value;
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
