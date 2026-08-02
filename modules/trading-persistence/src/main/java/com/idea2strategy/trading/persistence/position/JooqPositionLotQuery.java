package com.idea2strategy.trading.persistence.position;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqPositionLotQuery {
    private final DSLContext dsl;
    public JooqPositionLotQuery(DSLContext dsl){this.dsl=dsl;}
    public Optional<FlowPositionView> flow(UUID bot,UUID partition,UUID flow,UUID instrument){return dsl.fetchOptional("select quantity,cost_basis,realized_pnl,version,updated_at from trading.execution_flow_position_projection where bot_id=? and partition_id=? and flow_id=? and instrument_id=?",bot,partition,flow,instrument).map(r->new FlowPositionView(r.get("quantity",BigDecimal.class),r.get("cost_basis",BigDecimal.class),r.get("realized_pnl",BigDecimal.class),r.get("version",Long.class),r.get("updated_at",OffsetDateTime.class).toInstant()));}
    public List<LotView> lots(UUID bot,UUID partition,UUID flow,UUID instrument){return dsl.fetch("""
        select l.lot_id,l.opening_fill_record_id,l.opened_quantity,l.opened_cost_basis,l.opened_at,
               p.remaining_quantity,p.remaining_cost_basis,p.closed_at,p.version
        from trading.execution_position_lot l join trading.execution_position_lot_projection p on p.lot_id=l.lot_id
        where l.bot_id=? and l.partition_id=? and l.flow_id=? and l.instrument_id=? order by l.opened_at,l.lot_id
        """,bot,partition,flow,instrument).map(r->new LotView(r.get("lot_id",UUID.class),r.get("opening_fill_record_id",UUID.class),r.get("opened_quantity",BigDecimal.class),r.get("opened_cost_basis",BigDecimal.class),r.get("remaining_quantity",BigDecimal.class),r.get("remaining_cost_basis",BigDecimal.class),r.get("opened_at",OffsetDateTime.class).toInstant(),Optional.ofNullable(r.get("closed_at",OffsetDateTime.class)).map(OffsetDateTime::toInstant),r.get("version",Long.class)));}
    public List<MovementView> movements(UUID fill){return dsl.fetch("select lot_id,movement_type,quantity_delta,cost_basis_delta,realized_pnl,remaining_after,cost_basis_after,occurred_at from trading.execution_position_lot_movement where fill_record_id=? order by lot_id",fill).map(r->new MovementView(r.get("lot_id",UUID.class),r.get("movement_type",String.class),r.get("quantity_delta",BigDecimal.class),r.get("cost_basis_delta",BigDecimal.class),r.get("realized_pnl",BigDecimal.class),r.get("remaining_after",BigDecimal.class),r.get("cost_basis_after",BigDecimal.class),r.get("occurred_at",OffsetDateTime.class).toInstant()));}
    public record FlowPositionView(BigDecimal quantity,BigDecimal costBasis,BigDecimal realizedPnl,long version,java.time.Instant updatedAt){}
    public record LotView(UUID lotId,UUID openingFillRecordId,BigDecimal openedQuantity,BigDecimal openedCostBasis,BigDecimal remainingQuantity,BigDecimal remainingCostBasis,java.time.Instant openedAt,Optional<java.time.Instant> closedAt,long version){}
    public record MovementView(UUID lotId,String type,BigDecimal quantityDelta,BigDecimal costBasisDelta,BigDecimal realizedPnl,BigDecimal remainingAfter,BigDecimal costBasisAfter,java.time.Instant occurredAt){}
}
