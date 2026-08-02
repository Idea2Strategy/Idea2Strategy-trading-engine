package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record ResourceReservation(
        UUID reservationId,
        UUID createCommandId,
        String requestFingerprint,
        UUID orderId,
        ReservationResourceType resourceType,
        String resourceKey,
        BigDecimal reserved,
        BigDecimal consumed,
        BigDecimal released,
        ReservationStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String terminalReason,
        List<LotReservationAllocation> lotAllocations) {

    public ResourceReservation {
        reservationId = required(reservationId, "reservationId");
        createCommandId = required(createCommandId, "createCommandId");
        requestFingerprint = nonBlank(requestFingerprint, "requestFingerprint");
        orderId = required(orderId, "orderId");
        resourceType = required(resourceType, "resourceType");
        resourceKey = nonBlank(resourceKey, "resourceKey");
        reserved = positive(reserved, "reserved");
        consumed = nonNegative(consumed, "consumed");
        released = nonNegative(released, "released");
        status = required(status, "status");
        if (consumed.add(released).compareTo(reserved) > 0) {
            throw new IllegalArgumentException("consumed and released value exceeds reserved value");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        createdAt = required(createdAt, "createdAt");
        updatedAt = required(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        lotAllocations = normalizedAllocations(lotAllocations);
        validateShape(resourceType, resourceKey, reserved, consumed, released, status, version,
                createdAt, updatedAt, terminalReason, lotAllocations);
        validateIdentity(reservationId, createCommandId, requestFingerprint, orderId, resourceType,
                resourceKey, reserved, createdAt, lotAllocations, version);
    }

    public static ResourceReservation cash(UUID orderId, String currencyCode, BigDecimal amount, Instant createdAt) {
        String resourceKey = nonBlank(currencyCode, "currencyCode").toUpperCase(Locale.ROOT);
        if (!resourceKey.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currencyCode must be an ISO-like three-letter code");
        }
        return initial(orderId, ReservationResourceType.CASH_BUYING_POWER, resourceKey, amount, List.of(), createdAt);
    }

    public static ResourceReservation position(
            UUID orderId,
            UUID instrumentId,
            BigDecimal quantity,
            List<LotReservationAllocation> allocations,
            Instant createdAt) {
        return initial(orderId, ReservationResourceType.POSITION_QUANTITY,
                required(instrumentId, "instrumentId").toString(), quantity, allocations, createdAt);
    }

    private static ResourceReservation initial(
            UUID orderId,
            ReservationResourceType type,
            String resourceKey,
            BigDecimal reserved,
            List<LotReservationAllocation> allocations,
            Instant createdAt) {
        UUID requiredOrderId = required(orderId, "orderId");
        BigDecimal normalizedReserved = positive(reserved, "reserved");
        Instant requiredCreatedAt = required(createdAt, "createdAt");
        List<LotReservationAllocation> normalizedAllocations = normalizedAllocations(allocations);
        UUID id = ReservationIdentity.reservationId(requiredOrderId, type, resourceKey);
        return new ResourceReservation(id, ReservationIdentity.createCommandId(id),
                ReservationIdentity.fingerprint(requiredOrderId, type, resourceKey, normalizedReserved,
                        normalizedAllocations, requiredCreatedAt),
                requiredOrderId, type, resourceKey, normalizedReserved, BigDecimal.ZERO, BigDecimal.ZERO,
                ReservationStatus.ACTIVE, 1, requiredCreatedAt, requiredCreatedAt, null, normalizedAllocations);
    }

    public BigDecimal remaining() {
        return reserved.subtract(consumed).subtract(released).stripTrailingZeros();
    }

    public ResourceReservation consume(BigDecimal value, Instant occurredAt) {
        requireActive();
        BigDecimal delta = positive(value, "value");
        if (delta.compareTo(remaining()) > 0) {
            throw new IllegalArgumentException("consumption exceeds remaining reservation");
        }
        Instant transitionAt = transitionTime(occurredAt);
        List<LotReservationAllocation> allocations = resourceType == ReservationResourceType.POSITION_QUANTITY
                ? consumeLots(delta)
                : lotAllocations;
        BigDecimal nextConsumed = consumed.add(delta).stripTrailingZeros();
        ReservationStatus nextStatus = nextConsumed.add(released).compareTo(reserved) == 0
                ? ReservationStatus.SETTLED
                : ReservationStatus.ACTIVE;
        return copy(nextConsumed, released, nextStatus, transitionAt, null, allocations);
    }

    public ResourceReservation resize(
            BigDecimal targetReserved,
            List<LotReservationAllocation> targetLotAllocations,
            Instant occurredAt) {
        requireActive();
        BigDecimal target = positive(targetReserved, "targetReserved");
        if (target.compareTo(consumed.add(released)) <= 0) {
            throw new IllegalArgumentException("targetReserved must retain a positive unconsumed balance");
        }
        if (target.compareTo(reserved) == 0) {
            throw new IllegalArgumentException("targetReserved must change the reservation");
        }
        List<LotReservationAllocation> allocations = resourceType == ReservationResourceType.CASH_BUYING_POWER
                ? List.of()
                : normalizedAllocations(targetLotAllocations);
        return new ResourceReservation(reservationId, createCommandId, requestFingerprint, orderId, resourceType,
                resourceKey, target, consumed, released, ReservationStatus.ACTIVE, version + 1,
                createdAt, transitionTime(occurredAt), null, allocations);
    }

    public ResourceReservation releaseRemaining(Instant occurredAt, String reason) {
        requireActive();
        Instant transitionAt = transitionTime(occurredAt);
        String releaseReason = nonBlank(reason, "reason");
        BigDecimal nextReleased = released.add(remaining()).stripTrailingZeros();
        List<LotReservationAllocation> allocations = resourceType == ReservationResourceType.POSITION_QUANTITY
                ? lotAllocations.stream().map(LotReservationAllocation::releaseRemaining).toList()
                : lotAllocations;
        ReservationStatus nextStatus = consumed.signum() == 0 ? ReservationStatus.RELEASED : ReservationStatus.SETTLED;
        return copy(consumed, nextReleased, nextStatus, transitionAt, releaseReason, allocations);
    }

    private ResourceReservation copy(
            BigDecimal nextConsumed,
            BigDecimal nextReleased,
            ReservationStatus nextStatus,
            Instant transitionAt,
            String reason,
            List<LotReservationAllocation> allocations) {
        return new ResourceReservation(reservationId, createCommandId, requestFingerprint, orderId, resourceType,
                resourceKey, reserved, nextConsumed, nextReleased, nextStatus, version + 1,
                createdAt, transitionAt, reason, allocations);
    }

    private List<LotReservationAllocation> consumeLots(BigDecimal value) {
        BigDecimal remainingToConsume = value;
        List<LotReservationAllocation> result = new ArrayList<>(lotAllocations.size());
        for (LotReservationAllocation allocation : lotAllocations) {
            BigDecimal consumedHere = remainingToConsume.min(allocation.remaining());
            result.add(consumedHere.signum() == 0 ? allocation : allocation.consume(consumedHere));
            remainingToConsume = remainingToConsume.subtract(consumedHere);
        }
        if (remainingToConsume.signum() != 0) {
            throw new IllegalStateException("lot allocation does not cover reservation consumption");
        }
        return List.copyOf(result);
    }

    private void requireActive() {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException(status + " reservation is terminal");
        }
    }

    private Instant transitionTime(Instant occurredAt) {
        Instant value = required(occurredAt, "occurredAt");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException("occurredAt must not precede updatedAt");
        }
        return value;
    }

    private static void validateShape(
            ReservationResourceType type,
            String resourceKey,
            BigDecimal reserved,
            BigDecimal consumed,
            BigDecimal released,
            ReservationStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String reason,
            List<LotReservationAllocation> allocations) {
        if (type == ReservationResourceType.CASH_BUYING_POWER && !allocations.isEmpty()) {
            throw new IllegalArgumentException("cash reservation cannot contain lot allocations");
        }
        if (type == ReservationResourceType.POSITION_QUANTITY) {
            BigDecimal totalReserved = allocations.stream()
                    .map(LotReservationAllocation::reserved)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalConsumed = allocations.stream()
                    .map(LotReservationAllocation::consumed)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalReleased = allocations.stream()
                    .map(LotReservationAllocation::released)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalReserved.compareTo(reserved) != 0
                    || totalConsumed.compareTo(consumed) != 0
                    || totalReleased.compareTo(released) != 0) {
                throw new IllegalArgumentException("lot allocation total must match reservation totals");
            }
            UUID.fromString(resourceKey);
        }
        BigDecimal resolved = consumed.add(released);
        switch (status) {
            case ACTIVE -> {
                if (resolved.compareTo(reserved) >= 0 || reason != null) {
                    throw new IllegalArgumentException("ACTIVE reservation must retain an unsettled balance without reason");
                }
            }
            case SETTLED -> {
                if (resolved.compareTo(reserved) != 0 || consumed.signum() <= 0) {
                    throw new IllegalArgumentException("SETTLED reservation must fully resolve with consumption");
                }
            }
            case RELEASED -> {
                if (released.compareTo(reserved) != 0 || consumed.signum() != 0 || reason == null) {
                    throw new IllegalArgumentException("RELEASED reservation must release the full unused balance");
                }
            }
        }
        if (version == 1 && (status != ReservationStatus.ACTIVE || consumed.signum() != 0
                || released.signum() != 0 || !updatedAt.equals(createdAt))) {
            throw new IllegalArgumentException("version one must be the initial ACTIVE reservation");
        }
        if (version > 1 && updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    private static void validateIdentity(
            UUID reservationId,
            UUID createCommandId,
            String fingerprint,
            UUID orderId,
            ReservationResourceType type,
            String resourceKey,
            BigDecimal reserved,
            Instant createdAt,
            List<LotReservationAllocation> allocations,
            long version) {
        UUID expectedId = ReservationIdentity.reservationId(orderId, type, resourceKey);
        if (!expectedId.equals(reservationId)) {
            throw new IllegalArgumentException("reservationId does not match reservation identity");
        }
        if (!ReservationIdentity.createCommandId(expectedId).equals(createCommandId)) {
            throw new IllegalArgumentException("createCommandId does not match reservationId");
        }
        if (version > 1) {
            return;
        }
        String expectedFingerprint = ReservationIdentity.fingerprint(orderId, type, resourceKey, reserved,
                allocations.stream()
                        .map(allocation -> new LotReservationAllocation(allocation.lotId(), allocation.openedAt(),
                                allocation.reserved(), BigDecimal.ZERO, BigDecimal.ZERO))
                        .toList(),
                createdAt);
        if (!expectedFingerprint.equals(fingerprint)) {
            throw new IllegalArgumentException("requestFingerprint does not match initial reservation");
        }
    }

    private static List<LotReservationAllocation> normalizedAllocations(List<LotReservationAllocation> allocations) {
        List<LotReservationAllocation> values = allocations == null ? List.of() : List.copyOf(allocations);
        if (values.stream().map(LotReservationAllocation::lotId).distinct().count() != values.size()) {
            throw new IllegalArgumentException("lot allocations must have unique lot IDs");
        }
        return values.stream()
                .sorted(Comparator.comparing(LotReservationAllocation::openedAt)
                        .thenComparing(allocation -> allocation.lotId().toString()))
                .toList();
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal normalized = nonNegative(value, name);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return normalized;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal normalized = required(value, name).stripTrailingZeros();
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
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
