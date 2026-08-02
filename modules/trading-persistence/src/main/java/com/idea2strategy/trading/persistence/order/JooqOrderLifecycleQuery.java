package com.idea2strategy.trading.persistence.order;

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

/**
 * Reads the canonical order tables through the jOOQ query boundary.
 *
 * <p>Values are returned as the canonical columns hold them rather than mapped back into the
 * lifecycle vocabulary, so a caller checking that a write landed in the right shape sees the stored
 * truth and not a translation of it.
 */
@Repository
public class JooqOrderLifecycleQuery {
    private final DSLContext dsl;

    public JooqOrderLifecycleQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<OrderRowView> findOrder(UUID orderId) {
        return dsl.fetchOptional("""
                        select id, bot_id, partition_id, instrument_id, order_key, side, order_type,
                               time_in_force, requested_quantity, limit_price, stop_price,
                               trailing_offset_type, trailing_offset_value, broker_rules_version,
                               precision_rules_version, slippage_rate_bps, fee_policy_id,
                               accepted_event_id, accepted_at, expires_at, contract_hash
                        from trading.orders
                        where id = ?
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toOrder);
    }

    public Optional<ProjectionView> findProjection(UUID orderId) {
        return dsl.fetchOptional("""
                        select order_id, bot_id, partition_id, status, filled_quantity,
                               remaining_quantity, reserved_cash, reserved_quantity,
                               active_stop_price, last_order_event_sequence,
                               last_bot_event_sequence, updated_at
                        from trading.order_state_projections
                        where order_id = ?
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toProjection);
    }

    public List<ComponentView> findComponents(UUID orderId) {
        return dsl.fetch("""
                        select order_id, intent_id, component_quantity, component_sequence,
                               composition_rules_version
                        from trading.order_components
                        where order_id = ?
                        order by component_sequence
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toComponent);
    }

    public List<EventView> findEvents(UUID orderId) {
        return dsl.fetch("""
                        select order_id, bot_event_id, order_sequence, event_type, previous_status,
                               new_status, reason_code, occurred_at
                        from trading.order_events
                        where order_id = ?
                        order by order_sequence
                        """, Objects.requireNonNull(orderId, "orderId"))
                .map(JooqOrderLifecycleQuery::toEvent);
    }

    public int countOrders() {
        return dsl.fetchCount(dsl.selectFrom("trading.orders"));
    }

    public int countEvents() {
        return dsl.fetchCount(dsl.selectFrom("trading.order_events"));
    }

    private static OrderRowView toOrder(Record record) {
        return new OrderRowView(
                record.get("id", UUID.class),
                record.get("bot_id", UUID.class),
                record.get("partition_id", UUID.class),
                record.get("instrument_id", UUID.class),
                record.get("order_key", String.class),
                record.get("side", String.class),
                record.get("order_type", String.class),
                record.get("time_in_force", String.class),
                record.get("requested_quantity", BigDecimal.class),
                record.get("limit_price", BigDecimal.class),
                record.get("stop_price", BigDecimal.class),
                record.get("trailing_offset_type", String.class),
                record.get("trailing_offset_value", BigDecimal.class),
                record.get("broker_rules_version", String.class),
                record.get("precision_rules_version", String.class),
                record.get("slippage_rate_bps", Integer.class),
                record.get("fee_policy_id", UUID.class),
                record.get("accepted_event_id", UUID.class),
                instant(record, "accepted_at"),
                instant(record, "expires_at"),
                record.get("contract_hash", String.class));
    }

    private static ProjectionView toProjection(Record record) {
        return new ProjectionView(
                record.get("order_id", UUID.class),
                record.get("bot_id", UUID.class),
                record.get("partition_id", UUID.class),
                record.get("status", String.class),
                record.get("filled_quantity", BigDecimal.class),
                record.get("remaining_quantity", BigDecimal.class),
                record.get("reserved_cash", BigDecimal.class),
                record.get("reserved_quantity", BigDecimal.class),
                record.get("active_stop_price", BigDecimal.class),
                record.get("last_order_event_sequence", Long.class),
                record.get("last_bot_event_sequence", Long.class),
                instant(record, "updated_at"));
    }

    private static ComponentView toComponent(Record record) {
        return new ComponentView(
                record.get("order_id", UUID.class),
                record.get("intent_id", UUID.class),
                record.get("component_quantity", BigDecimal.class),
                record.get("component_sequence", Integer.class),
                record.get("composition_rules_version", String.class));
    }

    private static EventView toEvent(Record record) {
        return new EventView(
                record.get("order_id", UUID.class),
                record.get("bot_event_id", UUID.class),
                record.get("order_sequence", Long.class),
                record.get("event_type", String.class),
                record.get("previous_status", String.class),
                record.get("new_status", String.class),
                record.get("reason_code", String.class),
                instant(record, "occurred_at"));
    }

    private static Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record OrderRowView(
            UUID orderId, UUID botId, UUID partitionId, UUID instrumentId, String orderKey,
            String side, String orderType, String timeInForce, BigDecimal requestedQuantity,
            BigDecimal limitPrice, BigDecimal stopPrice, String trailingOffsetType,
            BigDecimal trailingOffsetValue, String brokerRulesVersion, String precisionRulesVersion,
            int slippageRateBps, UUID feePolicyId, UUID acceptedEventId, Instant acceptedAt,
            Instant expiresAt, String contractHash) {
    }

    public record ProjectionView(
            UUID orderId, UUID botId, UUID partitionId, String status, BigDecimal filledQuantity,
            BigDecimal remainingQuantity, BigDecimal reservedCash, BigDecimal reservedQuantity,
            BigDecimal activeStopPrice, long lastOrderEventSequence, long lastBotEventSequence,
            Instant updatedAt) {
    }

    public record ComponentView(
            UUID orderId, UUID intentId, BigDecimal componentQuantity, int componentSequence,
            String compositionRulesVersion) {
    }

    public record EventView(
            UUID orderId, UUID botEventId, long orderSequence, String eventType,
            String previousStatus, String newStatus, String reasonCode, Instant occurredAt) {
    }
}
