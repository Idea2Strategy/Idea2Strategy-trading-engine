package com.idea2strategy.trading.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One FIFO lot remainder a {@code POSITION_QUANTITY} reservation has locked, as canonical
 * {@code trading.position_lot_reservations} records it.
 *
 * <p>Canonical stores only which lot and how much of it is locked. It carries no per-lot consumed or
 * released column, because the lock exists to stop another flow selling the same remainder and is
 * held whole until the reservation reaches a terminal state. The private schema's per-lot
 * {@code consumed} and {@code released} therefore have no canonical home and are gone.
 *
 * <p>{@code openedAt} is not stored here either — it belongs to {@code trading.position_lots} — but
 * it is carried so the reservation can order its lots the way the FIFO close path will, and so a
 * reservation read back reports the same order it was written in.
 */
public record LotReservationAllocation(UUID lotId, Instant openedAt, BigDecimal reservedQuantity) {

    public LotReservationAllocation {
        lotId = ReservationValues.required(lotId, "lotId");
        openedAt = ReservationValues.required(openedAt, "openedAt");
        reservedQuantity = ReservationValues.positive(reservedQuantity, "reservedQuantity");
    }
}
