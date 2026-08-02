package com.idea2strategy.trading.domain.fill;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FillRecord(
        UUID fillRecordId,
        String requestFingerprint,
        UUID rootFillId,
        UUID correctionOfRecordId,
        UUID orderId,
        String sourceExecutionId,
        int revision,
        FillRecordKind kind,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        BigDecimal slippage,
        Instant occurredAt,
        Instant receivedAt) {

    public FillRecord {
        orderId = required(orderId, "orderId");
        sourceExecutionId = text(sourceExecutionId, "sourceExecutionId");
        kind = required(kind, "kind");
        quantity = nonNegative(quantity, "quantity");
        price = nonNegative(price, "price");
        commission = nonNegative(commission, "commission");
        slippage = nonNegative(slippage, "slippage");
        occurredAt = required(occurredAt, "occurredAt");
        receivedAt = required(receivedAt, "receivedAt");
        if (receivedAt.isBefore(occurredAt)) throw new IllegalArgumentException("receivedAt must not precede occurredAt");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");

        UUID expectedId = FillIdentity.recordId(orderId, sourceExecutionId, revision);
        UUID expectedRoot = FillIdentity.recordId(orderId, sourceExecutionId, 0);
        if (!expectedId.equals(fillRecordId) || !expectedRoot.equals(rootFillId)) {
            throw new IllegalArgumentException("fill identity does not match order, source execution and revision");
        }
        if (revision == 0) {
            if (kind != FillRecordKind.ORIGINAL || correctionOfRecordId != null) {
                throw new IllegalArgumentException("revision zero must be an original fill");
            }
        } else {
            UUID expectedPrevious = FillIdentity.recordId(orderId, sourceExecutionId, revision - 1);
            if (kind == FillRecordKind.ORIGINAL || !expectedPrevious.equals(correctionOfRecordId)) {
                throw new IllegalArgumentException("correction must reference the immediately preceding revision");
            }
        }
        if (kind == FillRecordKind.BUST) {
            if (quantity.signum() != 0 || price.signum() != 0 || commission.signum() != 0 || slippage.signum() != 0) {
                throw new IllegalArgumentException("bust must carry zero effective values");
            }
        } else if (quantity.signum() <= 0 || price.signum() <= 0) {
            throw new IllegalArgumentException("fill quantity and price must be positive");
        }

        String expectedFingerprint = fingerprint(orderId, sourceExecutionId, revision, kind, quantity, price,
                commission, slippage, occurredAt, receivedAt, correctionOfRecordId);
        if (!expectedFingerprint.equals(requestFingerprint)) {
            throw new IllegalArgumentException("requestFingerprint does not match fill payload");
        }
    }

    public static FillRecord original(
            UUID orderId, String sourceExecutionId, BigDecimal quantity, BigDecimal price,
            BigDecimal commission, BigDecimal slippage, Instant occurredAt, Instant receivedAt) {
        return create(orderId, sourceExecutionId, 0, FillRecordKind.ORIGINAL, null,
                quantity, price, commission, slippage, occurredAt, receivedAt);
    }

    public FillRecord corrected(
            BigDecimal correctedQuantity, BigDecimal correctedPrice, BigDecimal correctedCommission,
            BigDecimal correctedSlippage, Instant correctionOccurredAt, Instant correctionReceivedAt) {
        requireLatestTransitionTime(correctionOccurredAt);
        return create(orderId, sourceExecutionId, revision + 1, FillRecordKind.CORRECTION, fillRecordId,
                correctedQuantity, correctedPrice, correctedCommission, correctedSlippage,
                correctionOccurredAt, correctionReceivedAt);
    }

    public FillRecord busted(Instant bustOccurredAt, Instant bustReceivedAt) {
        requireLatestTransitionTime(bustOccurredAt);
        return create(orderId, sourceExecutionId, revision + 1, FillRecordKind.BUST, fillRecordId,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                bustOccurredAt, bustReceivedAt);
    }

    public boolean countsAsTrade() { return kind == FillRecordKind.ORIGINAL; }

    private void requireLatestTransitionTime(Instant next) {
        if (next == null || next.isBefore(occurredAt)) {
            throw new IllegalArgumentException("correction time must not precede the current revision");
        }
    }

    private static FillRecord create(
            UUID orderId, String sourceExecutionId, int revision, FillRecordKind kind, UUID previous,
            BigDecimal quantity, BigDecimal price, BigDecimal commission, BigDecimal slippage,
            Instant occurredAt, Instant receivedAt) {
        UUID id = FillIdentity.recordId(required(orderId, "orderId"), text(sourceExecutionId, "sourceExecutionId"), revision);
        BigDecimal normalizedQuantity = nonNegative(quantity, "quantity");
        BigDecimal normalizedPrice = nonNegative(price, "price");
        BigDecimal normalizedCommission = nonNegative(commission, "commission");
        BigDecimal normalizedSlippage = nonNegative(slippage, "slippage");
        String fingerprint = fingerprint(orderId, sourceExecutionId, revision, kind, normalizedQuantity,
                normalizedPrice, normalizedCommission, normalizedSlippage, occurredAt, receivedAt, previous);
        return new FillRecord(id, fingerprint, FillIdentity.recordId(orderId, sourceExecutionId, 0), previous,
                orderId, sourceExecutionId, revision, kind, normalizedQuantity, normalizedPrice,
                normalizedCommission, normalizedSlippage, occurredAt, receivedAt);
    }

    private static String fingerprint(UUID orderId, String sourceExecutionId, int revision, FillRecordKind kind,
                                      BigDecimal quantity, BigDecimal price, BigDecimal commission,
                                      BigDecimal slippage, Instant occurredAt, Instant receivedAt, UUID previous) {
        return FillIdentity.fingerprint(String.join("|", orderId.toString(), sourceExecutionId,
                Integer.toString(revision), kind.name(), decimal(quantity), decimal(price), decimal(commission),
                decimal(slippage), occurredAt.toString(), receivedAt.toString(), previous == null ? "" : previous.toString()));
    }

    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        return normalized;
    }
    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
