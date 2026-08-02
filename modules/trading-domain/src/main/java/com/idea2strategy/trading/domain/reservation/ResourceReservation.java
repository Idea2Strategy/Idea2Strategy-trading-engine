package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A reserved resource, as canonical {@code trading.resource_reservations} records it.
 *
 * <p><strong>A canonical reservation belongs to an intent, not to an order.</strong> Buying power is
 * committed when the intent is approved, which is before any order exists, so
 * {@code resource_reservations.intent_id} is the owner and the private schema's {@code order_id} has
 * no place here. The order only enters later, through {@link ReservationComponentLink} and canonical
 * {@code order_component_reservations}, once the intent has actually been composed into an order.
 *
 * <p>The state machine is unchanged — {@code ACTIVE}, {@code SETTLED}, {@code RELEASED}, consumption
 * and release monotone and conserving — but each transition now names the canonical event it
 * produces, because {@code assert_reservation_event_totals} rebuilds these totals from
 * {@code reservation_events} and refuses any projection the events do not explain.
 *
 * <p>Two private fields are gone rather than ported. {@code createCommandId} and
 * {@code requestFingerprint} existed to drive a private receipt table; canonical already makes
 * {@code (intent_id, reservation_key)} and {@code (reservation_id, event_key)} unique and carries an
 * {@code event_hash} on every event, so idempotency and divergence detection are native. The
 * free-text {@code terminalReason} becomes {@link ReservationReleaseCause}, which is the only form
 * canonical can store.
 */
public record ResourceReservation(
        UUID reservationId,
        UUID intentId,
        ReservationResourceType resourceType,
        String currencyCode,
        UUID instrumentId,
        BigDecimal reserved,
        BigDecimal consumed,
        BigDecimal released,
        ReservationStatus status,
        ReservationReleaseCause releaseCause,
        long lastEventSequence,
        Instant createdAt,
        Instant updatedAt,
        List<LotReservationAllocation> lotAllocations) {

    /** Canonical {@code reservation_events.reservation_sequence} of the {@code CREATED} event. */
    public static final long CREATED_SEQUENCE = 1L;

    public ResourceReservation {
        intentId = ReservationValues.required(intentId, "intentId");
        resourceType = ReservationValues.required(resourceType, "resourceType");
        currencyCode = currencyCode == null
                ? null
                : ReservationValues.currencyCode(currencyCode, "currencyCode");
        reserved = ReservationValues.positive(reserved, "reserved");
        consumed = ReservationValues.nonNegative(consumed, "consumed");
        released = ReservationValues.nonNegative(released, "released");
        status = ReservationValues.required(status, "status");
        createdAt = ReservationValues.required(createdAt, "createdAt");
        updatedAt = ReservationValues.required(updatedAt, "updatedAt");
        lotAllocations = normalize(lotAllocations);

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (lastEventSequence < CREATED_SEQUENCE) {
            throw new IllegalArgumentException("lastEventSequence must be positive");
        }
        // Canonical reservation_amount_not_exceeded / reservation_quantity_not_exceeded.
        if (consumed.add(released).compareTo(reserved) > 0) {
            throw new IllegalArgumentException("consumed and released exceed the reserved measure");
        }
        requireEvidence(resourceType, currencyCode, instrumentId, reserved, lotAllocations);
        requireStatusShape(status, releaseCause, reserved, consumed, released);
        if (lastEventSequence == CREATED_SEQUENCE
                && (status != ReservationStatus.ACTIVE || consumed.signum() != 0
                        || released.signum() != 0 || !updatedAt.equals(createdAt))) {
            throw new IllegalArgumentException("sequence one must be the reservation as created");
        }

        UUID derived = ReservationIdentity.reservationId(
                intentId, key(resourceType, currencyCode, instrumentId));
        if (reservationId == null) {
            reservationId = derived;
        } else if (!reservationId.equals(derived)) {
            throw new IllegalArgumentException("reservationId does not match the reservation key");
        }
    }

    /** Buying power committed against an approved intent, in one currency. */
    public static ResourceReservation cash(
            UUID intentId, String currencyCode, BigDecimal amount, Instant createdAt) {
        return initial(
                intentId, ReservationResourceType.CASH_BUYING_POWER,
                ReservationValues.currencyCode(currencyCode, "currencyCode"), null, amount,
                List.of(), createdAt);
    }

    /** Instrument quantity locked against the FIFO lots that will supply it. */
    public static ResourceReservation positionQuantity(
            UUID intentId,
            UUID instrumentId,
            BigDecimal quantity,
            List<LotReservationAllocation> lotAllocations,
            Instant createdAt) {
        return initial(
                intentId, ReservationResourceType.POSITION_QUANTITY, null, instrumentId, quantity,
                lotAllocations, createdAt);
    }

    /** Cash posted as collateral for a short sale of one instrument. */
    public static ResourceReservation shortCollateral(
            UUID intentId,
            String currencyCode,
            UUID instrumentId,
            BigDecimal amount,
            Instant createdAt) {
        return initial(
                intentId, ReservationResourceType.SHORT_COLLATERAL_CASH,
                ReservationValues.currencyCode(currencyCode, "currencyCode"), instrumentId, amount,
                List.of(), createdAt);
    }

    private static ResourceReservation initial(
            UUID intentId,
            ReservationResourceType resourceType,
            String currencyCode,
            UUID instrumentId,
            BigDecimal reserved,
            List<LotReservationAllocation> lotAllocations,
            Instant createdAt) {
        return new ResourceReservation(
                null, intentId, resourceType, currencyCode, instrumentId, reserved,
                ReservationValues.zero(), ReservationValues.zero(), ReservationStatus.ACTIVE, null,
                CREATED_SEQUENCE, createdAt, createdAt, lotAllocations);
    }

    /**
     * Canonical {@code reservation_key}: the normalised, never-null idempotency key that makes one
     * intent unable to reserve the same resource twice.
     */
    public String reservationKey() {
        return key(resourceType, currencyCode, instrumentId);
    }

    /** Whether the reserved measure is money rather than instrument quantity. */
    public boolean measuredInAmount() {
        return resourceType.measuredInAmount();
    }

    public BigDecimal remaining() {
        return reserved.subtract(consumed).subtract(released);
    }

    public Optional<ReservationReleaseCause> cause() {
        return Optional.ofNullable(releaseCause);
    }

    /**
     * One partial fill draws on the reservation and leaves it open for the rest of the order.
     *
     * <p>Canonical {@code partial_fill_consumption_stays_active} forces {@code status_after} to be
     * {@code ACTIVE} and {@code active_reservation_not_released} keeps the released total at zero
     * while it is, so the fill that exhausts the reservation is {@link #settleByFill} instead.
     */
    public ReservationTransition consumeByFill(BigDecimal amount, Instant occurredAt) {
        requireActive();
        BigDecimal delta = ReservationValues.positive(amount, "amount");
        if (delta.compareTo(remaining()) >= 0) {
            throw new IllegalArgumentException(
                    "a partial consumption must leave the reservation open; settle it instead");
        }
        return new ReservationTransition(
                next(consumed.add(delta), released, ReservationStatus.ACTIVE, null, occurredAt),
                ReservationEventType.CONSUMED_BY_FILL, delta, ReservationValues.zero(), occurredAt);
    }

    /**
     * The fill that finishes the order consumes what it actually used and hands back the rest.
     *
     * <p>Canonical records both halves on the single {@code SETTLED_BY_FILL} event, because
     * {@code reservation_amount_final_conservation} demands that a reservation which is no longer
     * {@code ACTIVE} has consumed plus released equal to what it reserved, and no release event type
     * describes an order that simply filled. The remainder is the buying-power buffer and the
     * difference between the estimated and the charged fee.
     */
    public ReservationTransition settleByFill(BigDecimal amount, Instant occurredAt) {
        requireActive();
        BigDecimal delta = ReservationValues.positive(amount, "amount");
        BigDecimal remaining = remaining();
        if (delta.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("settlement exceeds the remaining reservation");
        }
        BigDecimal releasedDelta = remaining.subtract(delta);
        return new ReservationTransition(
                next(consumed.add(delta), released.add(releasedDelta), ReservationStatus.SETTLED,
                        null, occurredAt),
                ReservationEventType.SETTLED_BY_FILL, delta, releasedDelta, occurredAt);
    }

    /**
     * The reservation is given back because the intent will not be filled, or not as reserved.
     *
     * <p>Canonical {@code released_reservation_has_no_consumption} reserves {@code RELEASED} for a
     * reservation nothing ever drew on. A release after a partial fill ends {@code SETTLED} instead,
     * which is exactly what {@code release_event_status_valid} was widened to allow when partial
     * consumption became legal.
     */
    public ReservationTransition release(ReservationReleaseCause cause, Instant occurredAt) {
        requireActive();
        ReservationValues.required(cause, "cause");
        BigDecimal releasedDelta = remaining();
        ReservationStatus nextStatus = consumed.signum() == 0
                ? ReservationStatus.RELEASED
                : ReservationStatus.SETTLED;
        return new ReservationTransition(
                next(consumed, released.add(releasedDelta), nextStatus, cause, occurredAt),
                cause.eventType(), ReservationValues.zero(), releasedDelta, occurredAt);
    }

    private ResourceReservation next(
            BigDecimal nextConsumed,
            BigDecimal nextReleased,
            ReservationStatus nextStatus,
            ReservationReleaseCause cause,
            Instant occurredAt) {
        Instant at = ReservationValues.required(occurredAt, "occurredAt");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("occurredAt must not precede updatedAt");
        }
        return new ResourceReservation(
                reservationId, intentId, resourceType, currencyCode, instrumentId, reserved,
                nextConsumed, nextReleased, nextStatus, cause, lastEventSequence + 1, createdAt, at,
                lotAllocations);
    }

    private void requireActive() {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException(status + " reservation is terminal");
        }
    }

    /** Mirrors the per-resource-type evidence canonical demands with row CHECK constraints. */
    private static void requireEvidence(
            ReservationResourceType resourceType,
            String currencyCode,
            UUID instrumentId,
            BigDecimal reserved,
            List<LotReservationAllocation> lotAllocations) {
        boolean needsCurrency = resourceType.measuredInAmount();
        boolean needsInstrument = resourceType != ReservationResourceType.CASH_BUYING_POWER;
        if (needsCurrency != (currencyCode != null)) {
            throw new IllegalArgumentException(resourceType + " currency evidence is wrong");
        }
        if (needsInstrument != (instrumentId != null)) {
            throw new IllegalArgumentException(resourceType + " instrument evidence is wrong");
        }
        if (resourceType != ReservationResourceType.POSITION_QUANTITY) {
            if (!lotAllocations.isEmpty()) {
                throw new IllegalArgumentException("only a quantity reservation can lock lots");
            }
            return;
        }
        BigDecimal locked = lotAllocations.stream()
                .map(LotReservationAllocation::reservedQuantity)
                .reduce(ReservationValues.zero(), BigDecimal::add);
        if (locked.compareTo(reserved) != 0) {
            throw new IllegalArgumentException("locked lot quantity must equal the reserved quantity");
        }
    }

    /** Mirrors the canonical status CHECK constraints on {@code resource_reservations}. */
    private static void requireStatusShape(
            ReservationStatus status,
            ReservationReleaseCause cause,
            BigDecimal reserved,
            BigDecimal consumed,
            BigDecimal released) {
        BigDecimal resolved = consumed.add(released);
        switch (status) {
            case ACTIVE -> {
                // active_reservation_not_released, plus the domain's own rule that a reservation
                // whose measure is spent is settled rather than left open.
                if (released.signum() != 0 || resolved.compareTo(reserved) >= 0 || cause != null) {
                    throw new IllegalArgumentException(
                            "an ACTIVE reservation keeps an unreleased balance and no cause");
                }
            }
            case SETTLED -> {
                if (resolved.compareTo(reserved) != 0 || consumed.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "a SETTLED reservation is fully resolved and has consumption");
                }
            }
            case RELEASED -> {
                if (released.compareTo(reserved) != 0 || consumed.signum() != 0 || cause == null) {
                    throw new IllegalArgumentException(
                            "a RELEASED reservation gave everything back and names a cause");
                }
            }
        }
    }

    private static String key(
            ReservationResourceType resourceType, String currencyCode, UUID instrumentId) {
        return switch (resourceType) {
            case CASH_BUYING_POWER -> resourceType.name() + ":" + currencyCode;
            case POSITION_QUANTITY -> resourceType.name() + ":" + instrumentId;
            case SHORT_COLLATERAL_CASH ->
                    resourceType.name() + ":" + currencyCode + ":" + instrumentId;
        };
    }

    private static List<LotReservationAllocation> normalize(
            List<LotReservationAllocation> lotAllocations) {
        List<LotReservationAllocation> values =
                lotAllocations == null ? List.of() : List.copyOf(lotAllocations);
        if (values.stream().map(LotReservationAllocation::lotId).distinct().count() != values.size()) {
            throw new IllegalArgumentException("a lot may back a reservation only once");
        }
        return values.stream()
                .sorted(Comparator.comparing(LotReservationAllocation::openedAt)
                        .thenComparing(allocation -> allocation.lotId().toString()))
                .toList();
    }
}
