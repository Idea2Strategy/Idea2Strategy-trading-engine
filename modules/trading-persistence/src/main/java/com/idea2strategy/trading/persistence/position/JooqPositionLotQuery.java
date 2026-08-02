package com.idea2strategy.trading.persistence.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Reads the canonical position tables back.
 *
 * <p>Only the three canonical views a caller of the write path needs: what a flow holds, which lots
 * it holds it in, and what one fill allocation moved. The partition roll-up is deliberately absent —
 * {@code CanonicalTradingReadSql} owns the ownership-gated read model, and this exists to prove the
 * write path rather than to serve it.
 */
public final class JooqPositionLotQuery {

    private final DSLContext dsl;

    public JooqPositionLotQuery(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<FlowPositionView> flow(UUID flowId, UUID instrumentId) {
        return dsl.fetchOptional("""
                        select long_quantity, short_quantity, cost_basis_amount, last_event_sequence,
                               projection_hash, updated_at
                        from trading.flow_position_projections
                        where flow_id = ? and instrument_id = ?
                        """, flowId, instrumentId)
                .map(record -> new FlowPositionView(
                        record.get("long_quantity", BigDecimal.class),
                        record.get("short_quantity", BigDecimal.class),
                        record.get("cost_basis_amount", BigDecimal.class),
                        record.get("last_event_sequence", Long.class),
                        record.get("projection_hash", String.class),
                        record.get("updated_at", OffsetDateTime.class).toInstant()));
    }

    public Optional<PartitionPositionView> partition(UUID partitionId, UUID instrumentId) {
        return dsl.fetchOptional("""
                        select net_quantity, average_cost, realized_pnl, valuation_status,
                               last_bot_event_sequence, updated_at
                        from trading.partition_position_projections
                        where partition_id = ? and instrument_id = ?
                        """, partitionId, instrumentId)
                .map(record -> new PartitionPositionView(
                        record.get("net_quantity", BigDecimal.class),
                        Optional.ofNullable(record.get("average_cost", BigDecimal.class)),
                        record.get("realized_pnl", BigDecimal.class),
                        record.get("valuation_status", String.class),
                        record.get("last_bot_event_sequence", Long.class),
                        record.get("updated_at", OffsetDateTime.class).toInstant()));
    }

    public List<LotView> lots(UUID botId, UUID partitionId, UUID flowId, UUID instrumentId) {
        return dsl.fetch("""
                        select lot.id, lot.opening_order_component_id, lot.opening_fill_allocation_id,
                               cast(lot.lot_side as varchar) as lot_side, lot.opened_quantity,
                               lot.unit_cost, lot.opened_cost_basis_amount, lot.opened_at,
                               projection.remaining_quantity, projection.remaining_cost_basis_amount,
                               projection.active_reserved_quantity, projection.last_movement_id,
                               projection.last_event_sequence, projection.closed_at
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.bot_id = ? and lot.partition_id = ? and lot.flow_id = ?
                          and lot.instrument_id = ?
                        order by lot.opened_at, lot.id
                        """, botId, partitionId, flowId, instrumentId)
                .map(record -> new LotView(
                        record.get("id", UUID.class),
                        record.get("opening_order_component_id", UUID.class),
                        record.get("opening_fill_allocation_id", UUID.class),
                        record.get("lot_side", String.class),
                        record.get("opened_quantity", BigDecimal.class),
                        record.get("unit_cost", BigDecimal.class),
                        record.get("opened_cost_basis_amount", BigDecimal.class),
                        record.get("remaining_quantity", BigDecimal.class),
                        record.get("remaining_cost_basis_amount", BigDecimal.class),
                        record.get("active_reserved_quantity", BigDecimal.class),
                        record.get("last_movement_id", UUID.class),
                        record.get("last_event_sequence", Long.class),
                        record.get("opened_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(record.get("closed_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)));
    }

    /** Every movement one fill allocation caused, newest lot last. */
    public List<MovementView> movements(UUID fillAllocationId) {
        return dsl.fetch("""
                        select id, position_lot_id, bot_event_id,
                               cast(movement_type as varchar) as movement_type,
                               quantity_delta, cost_basis_delta, remaining_after, cost_basis_after,
                               occurred_at
                        from trading.lot_movements
                        where source_fill_allocation_id = ?
                        order by position_lot_id
                        """, fillAllocationId)
                .map(record -> new MovementView(
                        record.get("id", UUID.class),
                        record.get("position_lot_id", UUID.class),
                        record.get("bot_event_id", UUID.class),
                        record.get("movement_type", String.class),
                        record.get("quantity_delta", BigDecimal.class),
                        record.get("cost_basis_delta", BigDecimal.class),
                        record.get("remaining_after", BigDecimal.class),
                        record.get("cost_basis_after", BigDecimal.class),
                        record.get("occurred_at", OffsetDateTime.class).toInstant()));
    }

    public record FlowPositionView(
            BigDecimal longQuantity,
            BigDecimal shortQuantity,
            BigDecimal costBasisAmount,
            long lastEventSequence,
            String projectionHash,
            Instant updatedAt) {
    }

    public record PartitionPositionView(
            BigDecimal netQuantity,
            Optional<BigDecimal> averageCost,
            BigDecimal realizedPnl,
            String valuationStatus,
            long lastBotEventSequence,
            Instant updatedAt) {
    }

    public record LotView(
            UUID lotId,
            UUID openingOrderComponentId,
            UUID openingFillAllocationId,
            String lotSide,
            BigDecimal openedQuantity,
            BigDecimal unitCost,
            BigDecimal openedCostBasisAmount,
            BigDecimal remainingQuantity,
            BigDecimal remainingCostBasisAmount,
            BigDecimal activeReservedQuantity,
            UUID lastMovementId,
            long lastEventSequence,
            Instant openedAt,
            Optional<Instant> closedAt) {
    }

    public record MovementView(
            UUID movementId,
            UUID positionLotId,
            UUID botEventId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal costBasisDelta,
            BigDecimal remainingAfter,
            BigDecimal costBasisAfter,
            Instant occurredAt) {
    }
}
