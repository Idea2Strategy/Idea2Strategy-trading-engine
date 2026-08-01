package com.idea2strategy.trading.persistence.order;

import com.idea2strategy.trading.domain.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqOrderLifecycleQuery {
    private final DSLContext dsl;

    public JooqOrderLifecycleQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<OrderLifecyclePersistenceView> findByOrderId(UUID orderId) {
        return dsl.fetchOptional("""
                        select order_id, create_command_id, request_fingerprint,
                               intent_id, candidate_id, instrument_id, side, quantity,
                               order_type, time_in_force, limit_price, stop_price, trail_percent,
                               expires_at, status, cumulative_filled_quantity, version,
                               created_at, last_transition_at, terminal_reason
                        from trading.trading_order
                        where order_id = ?
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toView);
    }

    public List<TransitionView> findTransitions(UUID orderId) {
        return dsl.fetch("""
                        select order_id, version, command_id, from_status, to_status,
                               fill_delta, cumulative_filled_quantity, occurred_at, reason
                        from trading.order_lifecycle_transition
                        where order_id = ?
                        order by version
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toTransition);
    }

    static OrderLifecyclePersistenceView toView(Record record) {
        return new OrderLifecyclePersistenceView(
                record.get("order_id", UUID.class),
                record.get("create_command_id", UUID.class),
                record.get("request_fingerprint", String.class),
                record.get("intent_id", UUID.class),
                record.get("candidate_id", UUID.class),
                record.get("instrument_id", UUID.class),
                record.get("side", String.class),
                record.get("quantity", BigDecimal.class),
                record.get("order_type", String.class),
                record.get("time_in_force", String.class),
                record.get("limit_price", BigDecimal.class),
                record.get("stop_price", BigDecimal.class),
                record.get("trail_percent", BigDecimal.class),
                instant(record, "expires_at"),
                record.get("status", String.class),
                record.get("cumulative_filled_quantity", BigDecimal.class),
                record.get("version", Long.class),
                instant(record, "created_at"),
                instant(record, "last_transition_at"),
                record.get("terminal_reason", String.class));
    }

    private static TransitionView toTransition(Record record) {
        String fromStatus = record.get("from_status", String.class);
        return new TransitionView(
                record.get("order_id", UUID.class),
                record.get("version", Long.class),
                record.get("command_id", UUID.class),
                fromStatus == null ? null : OrderStatus.valueOf(fromStatus),
                OrderStatus.valueOf(record.get("to_status", String.class)),
                record.get("fill_delta", BigDecimal.class),
                record.get("cumulative_filled_quantity", BigDecimal.class),
                instant(record, "occurred_at"),
                record.get("reason", String.class));
    }

    private static Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record TransitionView(
            UUID orderId,
            long version,
            UUID commandId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            BigDecimal fillDelta,
            BigDecimal cumulativeFilledQuantity,
            Instant occurredAt,
            String reason) {
    }
}
