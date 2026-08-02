package com.idea2strategy.trading.persistence.position;

import com.idea2strategy.trading.application.port.PositionLotStore;
import com.idea2strategy.trading.application.position.PositionConflictException;
import com.idea2strategy.trading.application.position.PositionMutationResult;
import com.idea2strategy.trading.domain.position.LotClose;
import com.idea2strategy.trading.domain.position.LotClosing;
import com.idea2strategy.trading.domain.position.LotOpening;
import com.idea2strategy.trading.domain.position.LotSide;
import com.idea2strategy.trading.domain.position.PositionLot;
import com.idea2strategy.trading.domain.order.OrderScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical position tables.
 *
 * <p>The private schema kept a lot, its remainder, its movements and a command receipt in five
 * {@code execution_*} tables that only this service understood. Canonical keeps the same facts but
 * insists on provenance: {@code trading.position_lots} names the exact fill allocation that bought
 * the lot, {@code trading.lot_movements} names the official event and the allocation behind every
 * change, and the three projections — per lot, per flow and per partition — are declared
 * rebuildable from those two.
 *
 * <p>The private receipt table is gone. Canonical already makes
 * {@code position_lots.opening_fill_allocation_id} unique and
 * {@code lot_movements (position_lot_id, bot_event_id)} unique, and both ids are derived from the
 * allocation, so a redelivery lands on the row it already wrote instead of on a second one. A
 * redelivery that reports different economics is caught earlier still: the allocation row is
 * immutable canonical evidence, so the store checks the reported figures against it rather than
 * against a fingerprint of the previous request.
 */
@Repository
public class PostgresPositionLotStore implements PositionLotStore {

    /** Canonical {@code numeric(28,8)} quantities and {@code numeric(24,8)} amounts. */
    private static final int SCALE = 8;

    /**
     * Canonical {@code partition_position_projections.valuation_status} when nothing has marked the
     * position to market. The position write path knows what a holding cost, never what it is
     * worth, so it records the cost and leaves the valuation columns to whatever owns market data.
     */
    private static final String UNVALUED = "UNVALUED";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresPositionLotStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public PositionMutationResult open(LotOpening opening) {
        Objects.requireNonNull(opening, "opening");
        return transaction.execute(status -> openInTransaction(opening));
    }

    @Override
    public PositionMutationResult closeLong(LotClosing closing) {
        Objects.requireNonNull(closing, "closing");
        if (closing.lotSide() != LotSide.LONG) {
            throw new IllegalArgumentException("only long lots can be closed by this path");
        }
        return transaction.execute(status -> closeInTransaction(closing));
    }

    private PositionMutationResult openInTransaction(LotOpening opening) {
        long sequence = botEventSequence(opening.scope(), opening.botEventId());
        requireAllocationMatches(
                opening.scope(), opening.openingFillAllocationId(), opening.openingOrderComponentId(),
                opening.flowId(), opening.instrumentId(), opening.openedQuantity(),
                opening.grossAmount(), opening.feeAmount(),
                opening.lotSide().openingPositionEffect());

        PositionLot lot = opening.lot(sequence);
        if (insertLot(lot) != 1) {
            return replayOpen(opening, lot);
        }

        UUID movementId = opening.openingMovementId();
        insertMovement(
                movementId, lot, opening.botEventId(), opening.openingFillAllocationId(), "OPEN",
                lot.openedQuantity(), lot.openedCostBasisAmount(), lot.openedQuantity(),
                lot.openedCostBasisAmount(), opening.openedAt());
        insertLotProjection(lot, movementId, opening.openedAt());

        FlowPosition ending = applyToFlow(
                opening.scope(), opening.flowId(), opening.instrumentId(), lot.openedQuantity(),
                BigDecimal.ZERO, lot.openedCostBasisAmount(), sequence, opening.openedAt(), true);
        refreshPartition(
                opening.scope(), opening.instrumentId(), BigDecimal.ZERO, sequence,
                opening.openedAt());

        return new PositionMutationResult(
                opening.openingFillAllocationId(), lot.openedQuantity(), lot.openedCostBasisAmount(),
                zero(), ending.longQuantity(), ending.shortQuantity(), ending.costBasisAmount(), 1);
    }

    private PositionMutationResult closeInTransaction(LotClosing closing) {
        long sequence = botEventSequence(closing.scope(), closing.botEventId());
        requireAllocationMatches(
                closing.scope(), closing.closingFillAllocationId(), closing.closingOrderComponentId(),
                closing.flowId(), closing.instrumentId(), closing.quantity(), closing.grossAmount(),
                closing.feeAmount(), closing.lotSide().closingPositionEffect());

        List<StoredMovement> applied = closeMovements(closing.closingFillAllocationId());
        if (!applied.isEmpty()) {
            return replayClose(closing, applied);
        }

        FlowPosition current = flowPosition(closing.flowId(), closing.instrumentId(), true)
                .orElseThrow(() -> conflict("the flow holds no position in this instrument"));
        if (closing.quantity().compareTo(current.longQuantity()) > 0) {
            throw conflict("close quantity exceeds the long position");
        }

        BigDecimal remaining = closing.quantity();
        BigDecimal grossRemaining = closing.grossAmount();
        BigDecimal feeRemaining = closing.feeAmount();
        BigDecimal basisReleased = zero();
        BigDecimal realized = zero();
        int affected = 0;

        for (PositionLot lot : openLots(closing)) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = remaining.min(lot.remainingQuantity());
            // The lot that exhausts the request takes whatever is left of the allocation, so the
            // movements add back up to exactly what the fill settled instead of to a rounded sum.
            boolean lastLot = take.compareTo(remaining) == 0;
            BigDecimal gross = lastLot ? grossRemaining : closing.share(closing.grossAmount(), take);
            BigDecimal fee = lastLot ? feeRemaining : closing.share(closing.feeAmount(), take);

            LotClose close = lot.close(
                    closing.closingFillAllocationId(), take, gross, fee, closing.occurredAt(),
                    sequence);
            PositionLot next = close.remainingLot();
            UUID movementId = closing.movementId(lot.lotId());
            insertMovement(
                    movementId, lot, closing.botEventId(), closing.closingFillAllocationId(),
                    "CLOSE", take.negate(), close.costBasisReleased().negate(),
                    next.remainingQuantity(), next.remainingCostBasisAmount(), closing.occurredAt());
            if (updateLotProjection(next, movementId, lot.lastEventSequence(), closing.occurredAt())
                    != 1) {
                throw conflict("the lot changed concurrently");
            }

            remaining = remaining.subtract(take);
            grossRemaining = grossRemaining.subtract(gross);
            feeRemaining = feeRemaining.subtract(fee);
            basisReleased = basisReleased.add(close.costBasisReleased());
            realized = realized.add(close.realizedPnl());
            affected++;
        }

        if (remaining.signum() != 0) {
            throw conflict("the FIFO lots do not cover the flow position");
        }

        FlowPosition ending = applyToFlow(
                closing.scope(), closing.flowId(), closing.instrumentId(), closing.quantity().negate(),
                zero(), basisReleased.negate(), sequence, closing.occurredAt(), false);
        refreshPartition(
                closing.scope(), closing.instrumentId(), realized, sequence, closing.occurredAt());

        return new PositionMutationResult(
                closing.closingFillAllocationId(), closing.quantity().negate(), basisReleased.negate(),
                realized, ending.longQuantity(), ending.shortQuantity(), ending.costBasisAmount(),
                affected);
    }

    /**
     * A lot already exists for this allocation. Canonical made the allocation unique, so this is a
     * redelivery rather than a second lot; the stored row still has to say the same thing before it
     * can be reported as one.
     */
    private PositionMutationResult replayOpen(LotOpening opening, PositionLot desired) {
        PositionLot stored = findLot(desired.lotId())
                .orElseThrow(() -> conflict("position lot identity conflict"));
        boolean sameLot = stored.openingOrderComponentId().equals(desired.openingOrderComponentId())
                && stored.lotSide() == desired.lotSide()
                && stored.flowId().equals(desired.flowId())
                && stored.instrumentId().equals(desired.instrumentId())
                && stored.openedQuantity().compareTo(desired.openedQuantity()) == 0
                && stored.openedCostBasisAmount().compareTo(desired.openedCostBasisAmount()) == 0
                && stored.openedAt().equals(desired.openedAt());
        if (!sameLot) {
            throw conflict("position lot identity conflict");
        }
        FlowPosition ending = flowPosition(opening.flowId(), opening.instrumentId(), false)
                .orElseThrow(() -> conflict("the opened lot has no flow projection"));
        return new PositionMutationResult(
                opening.openingFillAllocationId(), stored.openedQuantity(),
                stored.openedCostBasisAmount(), zero(), ending.longQuantity(),
                ending.shortQuantity(), ending.costBasisAmount(), 1);
    }

    /**
     * The movements canonical already holds for this allocation are the receipt. Realised profit is
     * not stored on a movement, so it is derived back from the allocation's net proceeds and the
     * cost basis those movements released.
     */
    private PositionMutationResult replayClose(LotClosing closing, List<StoredMovement> applied) {
        BigDecimal quantityDelta = zero();
        BigDecimal costBasisDelta = zero();
        for (StoredMovement movement : applied) {
            quantityDelta = quantityDelta.add(movement.quantityDelta());
            costBasisDelta = costBasisDelta.add(movement.costBasisDelta());
        }
        if (quantityDelta.negate().compareTo(closing.quantity()) != 0) {
            throw conflict("position close identity conflict");
        }
        FlowPosition ending = flowPosition(closing.flowId(), closing.instrumentId(), false)
                .orElseThrow(() -> conflict("the closed flow has no projection"));
        return new PositionMutationResult(
                closing.closingFillAllocationId(), quantityDelta, costBasisDelta,
                closing.netProceeds().add(costBasisDelta), ending.longQuantity(),
                ending.shortQuantity(), ending.costBasisAmount(), applied.size());
    }

    /**
     * Checks the reported economics against the canonical fill allocation.
     *
     * <p>Canonical asserts the same things at commit through
     * {@code assert_position_lot_provenance} and {@code assert_lot_movement_provenance}. Reading the
     * allocation here turns a deferred database failure into a readable one at the boundary that
     * caused it, and it is what makes a divergent redelivery a conflict instead of a second write.
     */
    private void requireAllocationMatches(
            OrderScope scope,
            UUID allocationId,
            UUID orderComponentId,
            UUID flowId,
            UUID instrumentId,
            BigDecimal quantity,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            String positionEffect) {
        AllocationFacts facts = jdbc.sql("""
                        select allocation.order_component_id, allocation.allocated_quantity,
                               allocation.allocated_gross_amount, allocation.allocated_fee_amount,
                               intent.flow_id, intent.instrument_id,
                               cast(intent.position_effect as varchar) as position_effect
                        from trading.fill_component_allocations allocation
                        join trading.order_components component
                          on component.id = allocation.order_component_id
                        join trading.order_intents intent on intent.id = component.intent_id
                        where allocation.id = :allocationId
                          and allocation.bot_id = :botId
                          and allocation.partition_id = :partitionId
                        """)
                .param("allocationId", allocationId)
                .param("botId", scope.botId())
                .param("partitionId", scope.partitionId())
                .query((resultSet, rowNumber) -> new AllocationFacts(
                        resultSet.getObject("order_component_id", UUID.class),
                        resultSet.getBigDecimal("allocated_quantity"),
                        resultSet.getBigDecimal("allocated_gross_amount"),
                        resultSet.getBigDecimal("allocated_fee_amount"),
                        resultSet.getObject("flow_id", UUID.class),
                        resultSet.getObject("instrument_id", UUID.class),
                        resultSet.getString("position_effect")))
                .optional()
                .orElseThrow(() -> conflict("no fill allocation in this partition backs the movement"));

        require(facts.orderComponentId().equals(orderComponentId), "component");
        require(facts.flowId().equals(flowId), "flow");
        require(facts.instrumentId().equals(instrumentId), "instrument");
        require(facts.positionEffect().equals(positionEffect), "position effect");
        require(facts.allocatedQuantity().compareTo(quantity) == 0, "quantity");
        require(facts.allocatedGrossAmount().compareTo(grossAmount) == 0, "gross amount");
        require(facts.allocatedFeeAmount().compareTo(feeAmount) == 0, "fee");
    }

    private static void require(boolean satisfied, String what) {
        if (!satisfied) {
            throw conflict("the reported " + what + " does not match the fill allocation");
        }
    }

    private int insertLot(PositionLot lot) {
        return jdbc.sql("""
                        insert into trading.position_lots (
                            id, bot_id, partition_id, flow_id, instrument_id,
                            opening_order_component_id, opening_fill_allocation_id, lot_side,
                            opened_quantity, unit_cost, opened_cost_basis_amount, opened_at
                        ) values (
                            :id, :botId, :partitionId, :flowId, :instrumentId,
                            :openingOrderComponentId, :openingFillAllocationId,
                            cast(:lotSide as trading.lot_side), :openedQuantity, :unitCost,
                            :openedCostBasisAmount, :openedAt
                        )
                        on conflict do nothing
                        """)
                .param("id", lot.lotId())
                .param("botId", lot.botId())
                .param("partitionId", lot.partitionId())
                .param("flowId", lot.flowId())
                .param("instrumentId", lot.instrumentId())
                .param("openingOrderComponentId", lot.openingOrderComponentId())
                .param("openingFillAllocationId", lot.openingFillAllocationId())
                .param("lotSide", lot.lotSide().name())
                .param("openedQuantity", lot.openedQuantity())
                .param("unitCost", lot.unitCost())
                .param("openedCostBasisAmount", lot.openedCostBasisAmount())
                .param("openedAt", offset(lot.openedAt()))
                .update();
    }

    private void insertMovement(
            UUID movementId,
            PositionLot lot,
            UUID botEventId,
            UUID allocationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal costBasisDelta,
            BigDecimal remainingAfter,
            BigDecimal costBasisAfter,
            Instant occurredAt) {
        int inserted = jdbc.sql("""
                        insert into trading.lot_movements (
                            id, bot_id, partition_id, position_lot_id, bot_event_id,
                            source_fill_allocation_id, movement_type, quantity_delta,
                            cost_basis_delta, remaining_after, cost_basis_after, occurred_at
                        ) values (
                            :id, :botId, :partitionId, :positionLotId, :botEventId,
                            :sourceFillAllocationId,
                            cast(:movementType as trading.lot_movement_type), :quantityDelta,
                            :costBasisDelta, :remainingAfter, :costBasisAfter, :occurredAt
                        )
                        on conflict do nothing
                        """)
                .param("id", movementId)
                .param("botId", lot.botId())
                .param("partitionId", lot.partitionId())
                .param("positionLotId", lot.lotId())
                .param("botEventId", botEventId)
                .param("sourceFillAllocationId", allocationId)
                .param("movementType", movementType)
                .param("quantityDelta", quantityDelta)
                .param("costBasisDelta", costBasisDelta)
                .param("remainingAfter", remainingAfter)
                .param("costBasisAfter", costBasisAfter)
                .param("occurredAt", offset(occurredAt))
                .update();
        if (inserted != 1) {
            throw conflict("the lot already moved on this event");
        }
    }

    private void insertLotProjection(PositionLot lot, UUID movementId, Instant updatedAt) {
        int inserted = jdbc.sql("""
                        insert into trading.position_lot_projections (
                            position_lot_id, remaining_quantity, remaining_cost_basis_amount,
                            active_reserved_quantity, last_movement_id, last_event_sequence,
                            closed_at, updated_at
                        ) values (
                            :positionLotId, :remainingQuantity, :remainingCostBasisAmount, 0,
                            :lastMovementId, :lastEventSequence, null, :updatedAt
                        )
                        """)
                .param("positionLotId", lot.lotId())
                .param("remainingQuantity", lot.remainingQuantity())
                .param("remainingCostBasisAmount", lot.remainingCostBasisAmount())
                .param("lastMovementId", movementId)
                .param("lastEventSequence", lot.lastEventSequence())
                .param("updatedAt", offset(updatedAt))
                .update();
        if (inserted != 1) {
            throw conflict("the lot projection could not be opened");
        }
    }

    /**
     * {@code active_reserved_quantity} is deliberately not written. Reservations own that column,
     * and canonical {@code lot_projection_reservation_within_remaining} is what stops this close
     * from consuming quantity another flow has already reserved.
     */
    private int updateLotProjection(
            PositionLot next, UUID movementId, long expectedSequence, Instant updatedAt) {
        return jdbc.sql("""
                        update trading.position_lot_projections
                        set remaining_quantity = :remainingQuantity,
                            remaining_cost_basis_amount = :remainingCostBasisAmount,
                            last_movement_id = :lastMovementId,
                            last_event_sequence = :lastEventSequence,
                            closed_at = :closedAt,
                            updated_at = :updatedAt
                        where position_lot_id = :positionLotId
                          and last_event_sequence = :expectedSequence
                        """)
                .param("remainingQuantity", next.remainingQuantity())
                .param("remainingCostBasisAmount", next.remainingCostBasisAmount())
                .param("lastMovementId", movementId)
                .param("lastEventSequence", next.lastEventSequence())
                .param("closedAt", next.closedAt().map(PostgresPositionLotStore::offset).orElse(null))
                .param("updatedAt", offset(updatedAt))
                .param("positionLotId", next.lotId())
                .param("expectedSequence", expectedSequence)
                .update();
    }

    private List<PositionLot> openLots(LotClosing closing) {
        return jdbc.sql("""
                        select lot.id, lot.bot_id, lot.partition_id, lot.flow_id, lot.instrument_id,
                               lot.opening_order_component_id, lot.opening_fill_allocation_id,
                               cast(lot.lot_side as varchar) as lot_side, lot.opened_quantity,
                               lot.unit_cost, lot.opened_cost_basis_amount, lot.opened_at,
                               projection.remaining_quantity, projection.remaining_cost_basis_amount,
                               projection.closed_at, projection.last_event_sequence
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.bot_id = :botId
                          and lot.partition_id = :partitionId
                          and lot.flow_id = :flowId
                          and lot.instrument_id = :instrumentId
                          and lot.lot_side = cast(:lotSide as trading.lot_side)
                          and projection.remaining_quantity > 0
                        order by lot.opened_at, lot.id
                        for update of projection
                        """)
                .param("botId", closing.scope().botId())
                .param("partitionId", closing.scope().partitionId())
                .param("flowId", closing.flowId())
                .param("instrumentId", closing.instrumentId())
                .param("lotSide", closing.lotSide().name())
                .query((resultSet, rowNumber) -> toLot(resultSet))
                .list();
    }

    private Optional<PositionLot> findLot(UUID lotId) {
        return jdbc.sql("""
                        select lot.id, lot.bot_id, lot.partition_id, lot.flow_id, lot.instrument_id,
                               lot.opening_order_component_id, lot.opening_fill_allocation_id,
                               cast(lot.lot_side as varchar) as lot_side, lot.opened_quantity,
                               lot.unit_cost, lot.opened_cost_basis_amount, lot.opened_at,
                               projection.remaining_quantity, projection.remaining_cost_basis_amount,
                               projection.closed_at, projection.last_event_sequence
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.id = :lotId
                        """)
                .param("lotId", lotId)
                .query((resultSet, rowNumber) -> toLot(resultSet))
                .optional();
    }

    private static PositionLot toLot(ResultSet resultSet) throws SQLException {
        return new PositionLot(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("bot_id", UUID.class),
                resultSet.getObject("partition_id", UUID.class),
                resultSet.getObject("flow_id", UUID.class),
                resultSet.getObject("instrument_id", UUID.class),
                resultSet.getObject("opening_order_component_id", UUID.class),
                resultSet.getObject("opening_fill_allocation_id", UUID.class),
                LotSide.valueOf(resultSet.getString("lot_side")),
                resultSet.getBigDecimal("opened_quantity"),
                resultSet.getBigDecimal("unit_cost"),
                resultSet.getBigDecimal("opened_cost_basis_amount"),
                resultSet.getBigDecimal("remaining_quantity"),
                resultSet.getBigDecimal("remaining_cost_basis_amount"),
                instant(resultSet.getObject("opened_at", OffsetDateTime.class)),
                Optional.ofNullable(resultSet.getObject("closed_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                resultSet.getLong("last_event_sequence"));
    }

    private List<StoredMovement> closeMovements(UUID allocationId) {
        return jdbc.sql("""
                        select position_lot_id, quantity_delta, cost_basis_delta
                        from trading.lot_movements
                        where source_fill_allocation_id = :allocationId
                          and movement_type = 'CLOSE'
                        order by position_lot_id
                        """)
                .param("allocationId", allocationId)
                .query((resultSet, rowNumber) -> new StoredMovement(
                        resultSet.getObject("position_lot_id", UUID.class),
                        resultSet.getBigDecimal("quantity_delta"),
                        resultSet.getBigDecimal("cost_basis_delta")))
                .list();
    }

    /**
     * Moves the flow projection and returns what it now holds.
     *
     * <p>Canonical splits the flow position into a long and a short side and hashes the result, so
     * the next state is computed here rather than accumulated in SQL: the hash has to be taken over
     * the values that were actually stored. {@code last_event_sequence} carries the optimistic lock
     * the private schema kept in a separate version column.
     */
    private FlowPosition applyToFlow(
            OrderScope scope,
            UUID flowId,
            UUID instrumentId,
            BigDecimal longDelta,
            BigDecimal shortDelta,
            BigDecimal costBasisDelta,
            long sequence,
            Instant updatedAt,
            boolean createIfAbsent) {
        if (createIfAbsent) {
            seedFlow(scope, flowId, instrumentId, sequence, updatedAt);
        }
        FlowPosition current = flowPosition(flowId, instrumentId, true)
                .orElseThrow(() -> conflict("the flow holds no position in this instrument"));
        FlowPosition next = new FlowPosition(
                current.longQuantity().add(longDelta),
                current.shortQuantity().add(shortDelta),
                current.costBasisAmount().add(costBasisDelta),
                sequence);
        int updated = jdbc.sql("""
                        update trading.flow_position_projections
                        set long_quantity = :longQuantity,
                            short_quantity = :shortQuantity,
                            cost_basis_amount = :costBasisAmount,
                            last_event_sequence = :lastEventSequence,
                            projection_hash = :projectionHash,
                            updated_at = :updatedAt
                        where flow_id = :flowId
                          and instrument_id = :instrumentId
                          and last_event_sequence = :expectedSequence
                        """)
                .param("longQuantity", next.longQuantity())
                .param("shortQuantity", next.shortQuantity())
                .param("costBasisAmount", next.costBasisAmount())
                .param("lastEventSequence", sequence)
                .param("projectionHash", flowProjectionHash(flowId, instrumentId, next))
                .param("updatedAt", offset(updatedAt))
                .param("flowId", flowId)
                .param("instrumentId", instrumentId)
                .param("expectedSequence", current.lastEventSequence())
                .update();
        if (updated != 1) {
            throw conflict("the flow position changed concurrently");
        }
        return next;
    }

    private void seedFlow(
            OrderScope scope, UUID flowId, UUID instrumentId, long sequence, Instant updatedAt) {
        FlowPosition empty = new FlowPosition(zero(), zero(), zero(), sequence);
        jdbc.sql("""
                        insert into trading.flow_position_projections (
                            flow_id, partition_id, bot_id, instrument_id, long_quantity,
                            short_quantity, cost_basis_amount, last_event_sequence, projection_hash,
                            updated_at
                        ) values (
                            :flowId, :partitionId, :botId, :instrumentId, 0, 0, 0,
                            :lastEventSequence, :projectionHash, :updatedAt
                        )
                        on conflict do nothing
                        """)
                .param("flowId", flowId)
                .param("partitionId", scope.partitionId())
                .param("botId", scope.botId())
                .param("instrumentId", instrumentId)
                .param("lastEventSequence", sequence)
                .param("projectionHash", flowProjectionHash(flowId, instrumentId, empty))
                .param("updatedAt", offset(updatedAt))
                .update();
    }

    private Optional<FlowPosition> flowPosition(UUID flowId, UUID instrumentId, boolean forUpdate) {
        return jdbc.sql("""
                        select long_quantity, short_quantity, cost_basis_amount, last_event_sequence
                        from trading.flow_position_projections
                        where flow_id = :flowId and instrument_id = :instrumentId
                        """ + (forUpdate ? " for update" : ""))
                .param("flowId", flowId)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new FlowPosition(
                        resultSet.getBigDecimal("long_quantity"),
                        resultSet.getBigDecimal("short_quantity"),
                        resultSet.getBigDecimal("cost_basis_amount"),
                        resultSet.getLong("last_event_sequence")))
                .optional();
    }

    /**
     * Rebuilds the partition position from the flow projections underneath it.
     *
     * <p>Canonical keeps no cost-basis column on the partition row, only an average, so the row is
     * recomputed from its flows rather than accumulated. The partition row is locked before the
     * flows are summed, which makes the last writer for an instrument the one that sees every
     * committed flow.
     *
     * <p>Realised profit is the exception: canonical records it only here, so it is accumulated.
     */
    private void refreshPartition(
            OrderScope scope,
            UUID instrumentId,
            BigDecimal realizedDelta,
            long sequence,
            Instant updatedAt) {
        jdbc.sql("""
                        insert into trading.partition_position_projections (
                            partition_id, bot_id, instrument_id, net_quantity, average_cost,
                            realized_pnl, valuation_status, last_bot_event_sequence, updated_at
                        ) values (
                            :partitionId, :botId, :instrumentId, 0, null, 0, :valuationStatus,
                            :lastBotEventSequence, :updatedAt
                        )
                        on conflict do nothing
                        """)
                .param("partitionId", scope.partitionId())
                .param("botId", scope.botId())
                .param("instrumentId", instrumentId)
                .param("valuationStatus", UNVALUED)
                .param("lastBotEventSequence", sequence)
                .param("updatedAt", offset(updatedAt))
                .update();
        jdbc.sql("""
                        select 1 from trading.partition_position_projections
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        for update
                        """)
                .param("partitionId", scope.partitionId())
                .param("instrumentId", instrumentId)
                .query(Integer.class)
                .single();

        PartitionTotals totals = jdbc.sql("""
                        select coalesce(sum(long_quantity - short_quantity), 0) as net_quantity,
                               coalesce(sum(cost_basis_amount), 0) as cost_basis_amount
                        from trading.flow_position_projections
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        """)
                .param("partitionId", scope.partitionId())
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new PartitionTotals(
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("cost_basis_amount")))
                .single();

        jdbc.sql("""
                        update trading.partition_position_projections
                        set net_quantity = :netQuantity,
                            average_cost = :averageCost,
                            realized_pnl = realized_pnl + :realizedDelta,
                            last_bot_event_sequence = :lastBotEventSequence,
                            updated_at = :updatedAt
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        """)
                .param("netQuantity", totals.netQuantity())
                .param("averageCost", totals.averageCost())
                .param("realizedDelta", realizedDelta)
                .param("lastBotEventSequence", sequence)
                .param("updatedAt", offset(updatedAt))
                .param("partitionId", scope.partitionId())
                .param("instrumentId", instrumentId)
                .update();
    }

    private long botEventSequence(OrderScope scope, UUID botEventId) {
        return jdbc.sql("""
                        select event_sequence from bot.bot_events
                        where id = :id and bot_id = :botId
                        """)
                .param("id", botEventId)
                .param("botId", scope.botId())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> conflict("no official event of this bot caused the movement"));
    }

    /**
     * Canonical indexes a digest of the flow projection so a rebuilt projection can be compared with
     * the one that was stored instead of merely looking plausible.
     */
    private static String flowProjectionHash(UUID flowId, UUID instrumentId, FlowPosition position) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        write(digest, "flow-position:v1");
        write(digest, flowId.toString());
        write(digest, instrumentId.toString());
        write(digest, position.longQuantity().toPlainString());
        write(digest, position.shortQuantity().toPlainString());
        write(digest, position.costBasisAmount().toPlainString());
        write(digest, Long.toString(position.lastEventSequence()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void write(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static PositionConflictException conflict(String message) {
        return new PositionConflictException(message);
    }

    private record AllocationFacts(
            UUID orderComponentId,
            BigDecimal allocatedQuantity,
            BigDecimal allocatedGrossAmount,
            BigDecimal allocatedFeeAmount,
            UUID flowId,
            UUID instrumentId,
            String positionEffect) {
    }

    private record StoredMovement(UUID positionLotId, BigDecimal quantityDelta, BigDecimal costBasisDelta) {
    }

    private record FlowPosition(
            BigDecimal longQuantity,
            BigDecimal shortQuantity,
            BigDecimal costBasisAmount,
            long lastEventSequence) {
    }

    private record PartitionTotals(BigDecimal netQuantity, BigDecimal costBasisAmount) {

        /**
         * Canonical {@code position_projection_average_cost_nonnegative} allows no average on a
         * position that is flat, and this write path never opens a short, so a partition that is not
         * net long reports no average cost rather than a negative one.
         */
        BigDecimal averageCost() {
            return netQuantity.signum() > 0
                    ? costBasisAmount.divide(netQuantity, SCALE, RoundingMode.HALF_EVEN)
                    : null;
        }
    }
}
