package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.domain.reservation.ReservationEventType;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Reads back what the reservation write path put into the canonical tables. */
public final class JooqResourceReservationQuery {

    private final DSLContext dsl;

    public JooqResourceReservationQuery(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<ResourceReservationPersistenceView> findByReservationId(UUID reservationId) {
        return dsl.fetchOptional(
                        "select * from trading.resource_reservations where id = ?", reservationId)
                .map(JooqResourceReservationQuery::view);
    }

    /** Every reservation of one intent, in the order canonical would rebuild them. */
    public List<ResourceReservationPersistenceView> findByIntentId(UUID intentId) {
        return dsl.fetch(
                        "select * from trading.resource_reservations where intent_id = ?"
                                + " order by reservation_key", intentId)
                .map(JooqResourceReservationQuery::view);
    }

    /** The append-only event stream {@code assert_reservation_event_totals} rebuilds from. */
    public List<ReservationEventView> findEvents(UUID reservationId) {
        return dsl.fetch("""
                        select reservation_sequence, cast(event_type as varchar) as event_type,
                               bot_event_id, source_fill_id, event_key, consumed_amount_delta,
                               released_amount_delta, consumed_quantity_delta,
                               released_quantity_delta, cast(status_after as varchar) as status_after,
                               occurred_at, event_hash
                          from trading.reservation_events
                         where reservation_id = ?
                         order by reservation_sequence
                        """, reservationId)
                .map(row -> new ReservationEventView(
                        row.get("reservation_sequence", Long.class),
                        ReservationEventType.valueOf(row.get("event_type", String.class)),
                        row.get("bot_event_id", UUID.class),
                        row.get("source_fill_id", UUID.class),
                        row.get("event_key", String.class),
                        row.get("consumed_amount_delta", BigDecimal.class),
                        row.get("released_amount_delta", BigDecimal.class),
                        row.get("consumed_quantity_delta", BigDecimal.class),
                        row.get("released_quantity_delta", BigDecimal.class),
                        ReservationStatus.valueOf(row.get("status_after", String.class)),
                        row.get("occurred_at", OffsetDateTime.class).toInstant(),
                        row.get("event_hash", String.class)));
    }

    /** The order component this reservation backs, once the intent has been composed into an order. */
    public Optional<UUID> findOrderComponentId(UUID reservationId) {
        return dsl.fetchOptional(
                        "select order_component_id from trading.order_component_reservations"
                                + " where reservation_id = ?", reservationId)
                .map(row -> row.get("order_component_id", UUID.class));
    }

    /** The FIFO lot remainders a quantity reservation has locked. */
    public List<LotLockView> findLotLocks(UUID reservationId) {
        return dsl.fetch("""
                        select lock_row.position_lot_id, lock_row.reserved_quantity,
                               projection.active_reserved_quantity, projection.remaining_quantity
                          from trading.position_lot_reservations lock_row
                          join trading.position_lot_projections projection
                            on projection.position_lot_id = lock_row.position_lot_id
                          join trading.position_lots lot on lot.id = lock_row.position_lot_id
                         where lock_row.reservation_id = ?
                         order by lot.opened_at, lot.id
                        """, reservationId)
                .map(row -> new LotLockView(
                        row.get("position_lot_id", UUID.class),
                        row.get("reserved_quantity", BigDecimal.class),
                        row.get("active_reserved_quantity", BigDecimal.class),
                        row.get("remaining_quantity", BigDecimal.class)));
    }

    private static ResourceReservationPersistenceView view(Record row) {
        return new ResourceReservationPersistenceView(
                row.get("id", UUID.class),
                row.get("reservation_key", String.class),
                row.get("bot_id", UUID.class),
                row.get("partition_id", UUID.class),
                row.get("flow_id", UUID.class),
                row.get("intent_id", UUID.class),
                ReservationResourceType.valueOf(String.valueOf(row.get("resource_type"))),
                row.get("currency_code", String.class),
                row.get("instrument_id", UUID.class),
                row.get("buffer_policy_id", UUID.class),
                row.get("fee_policy_id", UUID.class),
                row.get("short_risk_policy_id", UUID.class),
                row.get("precision_rules_version", String.class),
                ReservationStatus.valueOf(String.valueOf(row.get("status"))),
                row.get("reference_price", BigDecimal.class),
                instant(row.get("reference_observed_at", OffsetDateTime.class)),
                row.get("reference_market_hash", String.class),
                row.get("base_notional", BigDecimal.class),
                row.get("fixed_slippage_amount", BigDecimal.class),
                row.get("estimated_fee_amount", BigDecimal.class),
                row.get("buffer_amount", BigDecimal.class),
                row.get("reserved_amount", BigDecimal.class),
                row.get("consumed_amount", BigDecimal.class),
                row.get("released_amount", BigDecimal.class),
                row.get("reserved_quantity", BigDecimal.class),
                row.get("consumed_quantity", BigDecimal.class),
                row.get("released_quantity", BigDecimal.class),
                row.get("created_event_id", UUID.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                row.get("last_event_sequence", Long.class));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** One canonical {@code trading.reservation_events} row. */
    public record ReservationEventView(
            long sequence,
            ReservationEventType eventType,
            UUID botEventId,
            UUID sourceFillId,
            String eventKey,
            BigDecimal consumedAmountDelta,
            BigDecimal releasedAmountDelta,
            BigDecimal consumedQuantityDelta,
            BigDecimal releasedQuantityDelta,
            ReservationStatus statusAfter,
            Instant occurredAt,
            String eventHash) {}

    /** One locked lot remainder, with what the lot projection now says about it. */
    public record LotLockView(
            UUID positionLotId,
            BigDecimal reservedQuantity,
            BigDecimal activeReservedQuantity,
            BigDecimal remainingQuantity) {}
}
