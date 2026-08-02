package com.idea2strategy.trading.persistence.reservation;

import com.idea2strategy.trading.domain.reservation.LotReservationAllocation;
import com.idea2strategy.trading.domain.reservation.ReservationResourceType;
import com.idea2strategy.trading.domain.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqResourceReservationQuery {
    private final DSLContext dsl;
    public JooqResourceReservationQuery(DSLContext dsl) { this.dsl = dsl; }

    public Optional<ResourceReservationPersistenceView> findByReservationId(UUID id) {
        return dsl.fetchOptional("select * from trading.execution_resource_reservation where reservation_id = ?", id)
                .map(this::view);
    }

    public List<MovementView> findMovements(UUID id) {
        return dsl.fetch("""
                select version, command_id, movement_type, amount, occurred_at, reason
                from trading.execution_resource_reservation_movement
                where reservation_id = ? order by version
                """, id).map(record -> new MovementView(
                record.get("version", Long.class), record.get("command_id", UUID.class),
                record.get("movement_type", String.class), record.get("amount", BigDecimal.class),
                record.get("occurred_at", OffsetDateTime.class).toInstant(), record.get("reason", String.class)));
    }

    private ResourceReservationPersistenceView view(Record row) {
        UUID id = row.get("reservation_id", UUID.class);
        List<LotReservationAllocation> lots = dsl.fetch("""
                select lot_id, opened_at, reserved, consumed, released
                from trading.execution_resource_reservation_lot
                where reservation_id = ? order by opened_at, lot_id
                """, id).map(lot -> new LotReservationAllocation(
                lot.get("lot_id", UUID.class), lot.get("opened_at", OffsetDateTime.class).toInstant(),
                lot.get("reserved", BigDecimal.class), lot.get("consumed", BigDecimal.class),
                lot.get("released", BigDecimal.class)));
        return new ResourceReservationPersistenceView(id, row.get("create_command_id", UUID.class),
                row.get("request_fingerprint", String.class), row.get("order_id", UUID.class),
                ReservationResourceType.valueOf(row.get("resource_type", String.class)),
                row.get("resource_key", String.class), row.get("reserved", BigDecimal.class),
                row.get("consumed", BigDecimal.class), row.get("released", BigDecimal.class),
                ReservationStatus.valueOf(row.get("status", String.class)), row.get("version", Long.class),
                row.get("created_at", OffsetDateTime.class).toInstant(),
                row.get("updated_at", OffsetDateTime.class).toInstant(), row.get("terminal_reason", String.class), lots);
    }

    public record MovementView(long version, UUID commandId, String type, BigDecimal amount,
                               java.time.Instant occurredAt, String reason) {}
}
