package com.idea2strategy.trading.persistence.corporateaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Reads a corporate action and its canonical effect back.
 *
 * <p>The private schema answered "what did this action do" from its own
 * {@code execution_corporate_action_lot_adjustment} table. Canonical answers it from the movements
 * themselves, which is the whole point of recording the adjustment as history: the confirmed action
 * of the market data owner and the lot movements that cite it are the only two things to read, and
 * neither is a summary this service maintains.
 */
public final class JooqCorporateActionQuery {

    private final DSLContext dsl;

    public JooqCorporateActionQuery(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The confirmed action as the market data owner published it. */
    public Optional<ConfirmedActionView> action(UUID corporateActionId) {
        return dsl.fetchOptional("""
                        select corporate_action.instrument_id, corporate_action.action_type,
                               corporate_action.effective_at, corporate_action.terms_hash,
                               corporate_action.terms_document #>> '{ratio,to}' as ratio_to,
                               corporate_action.terms_document #>> '{ratio,from}' as ratio_from,
                               cast(manifest.status as varchar) as manifest_status
                        from market_data.corporate_actions corporate_action
                        join market_data.dataset_manifests manifest
                          on manifest.id = corporate_action.source_manifest_id
                        where corporate_action.id = ?
                        """, corporateActionId)
                .map(record -> new ConfirmedActionView(
                        record.get("instrument_id", UUID.class),
                        record.get("action_type", String.class),
                        record.get("effective_at", OffsetDateTime.class).toInstant(),
                        record.get("terms_hash", String.class),
                        Long.parseLong(record.get("ratio_to", String.class)),
                        Long.parseLong(record.get("ratio_from", String.class)),
                        record.get("manifest_status", String.class)));
    }

    /** Every lot movement that cites the action, oldest lot first. */
    public List<AdjustmentView> adjustments(UUID corporateActionId) {
        return dsl.fetch("""
                        select movement.id, movement.bot_id, movement.partition_id,
                               movement.position_lot_id, movement.bot_event_id, lot.flow_id,
                               cast(movement.movement_type as varchar) as movement_type,
                               movement.quantity_delta, movement.cost_basis_delta,
                               movement.remaining_after, movement.cost_basis_after,
                               movement.occurred_at
                        from trading.lot_movements movement
                        join trading.position_lots lot on lot.id = movement.position_lot_id
                        where movement.corporate_action_id = ?
                        order by lot.opened_at, lot.id
                        """, corporateActionId)
                .map(record -> new AdjustmentView(
                        record.get("id", UUID.class),
                        record.get("bot_id", UUID.class),
                        record.get("partition_id", UUID.class),
                        record.get("position_lot_id", UUID.class),
                        record.get("bot_event_id", UUID.class),
                        record.get("flow_id", UUID.class),
                        record.get("movement_type", String.class),
                        record.get("quantity_delta", BigDecimal.class),
                        record.get("cost_basis_delta", BigDecimal.class),
                        record.get("remaining_after", BigDecimal.class),
                        record.get("cost_basis_after", BigDecimal.class),
                        record.get("occurred_at", OffsetDateTime.class).toInstant()));
    }

    public record ConfirmedActionView(
            UUID instrumentId,
            String actionType,
            Instant effectiveAt,
            String termsHash,
            long numerator,
            long denominator,
            String manifestStatus) {
    }

    public record AdjustmentView(
            UUID movementId,
            UUID botId,
            UUID partitionId,
            UUID positionLotId,
            UUID botEventId,
            UUID flowId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal costBasisDelta,
            BigDecimal remainingAfter,
            BigDecimal costBasisAfter,
            Instant occurredAt) {
    }
}
