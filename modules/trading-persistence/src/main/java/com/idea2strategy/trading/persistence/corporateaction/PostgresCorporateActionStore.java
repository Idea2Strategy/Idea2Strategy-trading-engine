package com.idea2strategy.trading.persistence.corporateaction;

import com.idea2strategy.trading.application.corporateaction.CorporateActionApplicationResult;
import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApplication;
import com.idea2strategy.trading.domain.corporateaction.SplitAdjustment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies an approved corporate action to the canonical position tables.
 *
 * <p>The private schema owned the whole event: it wrote the action, its approval and a per-lot
 * adjustment row into two {@code execution_corporate_action_*} tables that only this service
 * understood, then overwrote the lot projections in place. Canonical splits that in two, and the
 * split is the point of the migration.
 *
 * <p>The <em>action</em> is not ours. {@code market_data.corporate_actions} is written by the market
 * data pipeline, keyed by {@code (source_manifest_id, provider_event_key)}, hashed into
 * {@code terms_hash} and superseded by later revisions rather than edited. This store therefore
 * never writes it: it reads it and refuses to act on anything it cannot find there, whose terms
 * disagree with what was asked, whose source dataset is not {@code AVAILABLE}, or which a later
 * revision has already superseded. That is the product rule — only a split the data owner approved
 * and confirmed may be applied, and nothing derived here may stand in for it — expressed as
 * canonical evidence instead of as a fingerprint this service computed for itself.
 *
 * <p>The <em>adjustment</em> is ours, and canonical records it as history rather than as an
 * overwrite: one append-only {@code trading.lot_movements} row per lot with
 * {@code movement_type = 'CORPORATE_ACTION_ADJUSTMENT'} and {@code corporate_action_id} set, which
 * is what {@code corporate_movement_source_required} and {@code lot_movement_source_not_ambiguous}
 * demand. {@code trading.position_lots} is left untouched: it is the immutable evidence of what the
 * lot cost when it was opened, and a split does not change that. Only
 * {@code position_lot_projections} moves, and it moves to a value the movement row already explains.
 *
 * <p>Scope follows from provenance. Every canonical lot movement names an official
 * {@code bot.bot_events} row of the bot that owns the lot, so a single instrument-wide sweep across
 * every bot cannot exist. The action is applied once per bot against that bot's own event, and the
 * private {@code request_fingerprint} receipt is gone: the movement ids are derived from
 * (action, lot, event) and {@code lot_movements (position_lot_id, bot_event_id)} is UNIQUE, so a
 * redelivery lands on the rows it already wrote.
 */
@Repository
public class PostgresCorporateActionStore implements CorporateActionStore {

    /**
     * A split moves no money. It re-denominates a holding, so the cost basis is carried across
     * unchanged and nothing is posted to the official ledger.
     */
    public static final String NO_MONETARY_POSTING = "NO_MONETARY_POSTING_COST_BASIS_PRESERVED";

    /** Canonical {@code numeric(28,8)} quantities and {@code numeric(24,8)} amounts. */
    private static final int SCALE = SplitAdjustment.SCALE;

    private static final String MOVEMENT_TYPE = "CORPORATE_ACTION_ADJUSTMENT";

    /** The only {@code market_data.dataset_status} that means the data owner confirmed the feed. */
    private static final String AVAILABLE = "AVAILABLE";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresCorporateActionStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public CorporateActionApplicationResult apply(CorporateActionApplication application) {
        Objects.requireNonNull(application, "application");
        return transaction.execute(status -> applyInTransaction(application));
    }

    private CorporateActionApplicationResult applyInTransaction(
            CorporateActionApplication application) {
        ApprovedCorporateAction action = application.action();
        requireConfirmedCorporateAction(action);

        List<StoredAdjustment> alreadyApplied =
                appliedAdjustments(action.actionId(), application.botId());
        if (!alreadyApplied.isEmpty()) {
            return replay(application, alreadyApplied);
        }

        long sequence = botEventSequence(application.botId(), application.botEventId());
        List<OpenLot> lots = openLots(application.botId(), action.instrumentId());
        Set<FlowKey> flows = new LinkedHashSet<>();
        Set<UUID> partitions = new LinkedHashSet<>();

        for (OpenLot lot : lots) {
            SplitAdjustment adjustment = SplitAdjustment.exact(
                    lot.remainingQuantity(), lot.remainingCostBasisAmount(),
                    action.numerator(), action.denominator());
            UUID movementId = application.movementId(lot.lotId());
            insertMovement(application, lot, movementId, adjustment);
            if (updateLotProjection(lot, movementId, adjustment, sequence, action.effectiveAt())
                    != 1) {
                throw conflict("the lot changed during the corporate action");
            }
            flows.add(new FlowKey(lot.partitionId(), lot.flowId()));
            partitions.add(lot.partitionId());
        }

        for (FlowKey flow : flows) {
            refreshFlow(flow, action.instrumentId(), sequence, action.effectiveAt());
        }
        for (UUID partitionId : partitions) {
            refreshPartition(partitionId, action.instrumentId(), sequence, action.effectiveAt());
        }

        return new CorporateActionApplicationResult(
                action.actionId(), application.botId(), lots.size(), flows.size(),
                NO_MONETARY_POSTING);
    }

    /**
     * The movements canonical already holds for this action and bot are the receipt.
     *
     * <p>They are only a redelivery of <em>this</em> request if they were caused by the same
     * official event; a second, different event that claims the same action is a genuine conflict
     * rather than a replay, and canonical would otherwise happily apply the split twice.
     */
    private CorporateActionApplicationResult replay(
            CorporateActionApplication application, List<StoredAdjustment> applied) {
        boolean sameEvent = applied.stream()
                .allMatch(movement -> movement.botEventId().equals(application.botEventId()));
        if (!sameEvent) {
            throw conflict("the corporate action was already applied on another official event");
        }
        long flows = applied.stream().map(StoredAdjustment::flowId).distinct().count();
        return new CorporateActionApplicationResult(
                application.action().actionId(), application.botId(), applied.size(),
                (int) flows, NO_MONETARY_POSTING);
    }

    /**
     * Checks the approved action against the canonical corporate-action record.
     *
     * <p>Canonical asserts none of this for us — {@code lot_movements.corporate_action_id} only has
     * to point at some row — so the gate has to be read here. Everything it compares is immutable
     * evidence the market data pipeline wrote, which is what makes a redelivery carrying different
     * terms a conflict rather than a second application, and what makes the approved ratio
     * something the data owner published rather than something this service worked out.
     */
    private void requireConfirmedCorporateAction(ApprovedCorporateAction action) {
        CanonicalCorporateAction stored = jdbc.sql("""
                        select corporate_action.instrument_id, corporate_action.action_type,
                               corporate_action.effective_at, corporate_action.terms_hash,
                               corporate_action.terms_document ->> 'actionType' as terms_action_type,
                               corporate_action.terms_document #>> '{ratio,to}' as ratio_to,
                               corporate_action.terms_document #>> '{ratio,from}' as ratio_from,
                               cast(manifest.status as varchar) as manifest_status,
                               exists (
                                   select 1 from market_data.corporate_actions later
                                    where later.supersedes_action_id = corporate_action.id
                               ) as superseded
                        from market_data.corporate_actions corporate_action
                        join market_data.dataset_manifests manifest
                          on manifest.id = corporate_action.source_manifest_id
                        where corporate_action.id = :actionId
                        """)
                .param("actionId", action.actionId())
                .query((resultSet, rowNumber) -> new CanonicalCorporateAction(
                        resultSet.getObject("instrument_id", UUID.class),
                        resultSet.getString("action_type"),
                        resultSet.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("terms_hash"),
                        resultSet.getString("terms_action_type"),
                        resultSet.getString("ratio_to"),
                        resultSet.getString("ratio_from"),
                        resultSet.getString("manifest_status"),
                        resultSet.getBoolean("superseded")))
                .optional()
                .orElseThrow(() -> conflict(
                        "no confirmed corporate action of the market data owner backs this request"));

        if (!AVAILABLE.equals(stored.manifestStatus())) {
            throw conflict("the source dataset of the corporate action is "
                    + stored.manifestStatus() + " rather than " + AVAILABLE);
        }
        if (stored.superseded()) {
            throw conflict("the corporate action has been superseded by a later revision");
        }
        require(stored.instrumentId().equals(action.instrumentId()), "instrument");
        require(stored.actionType().equals(action.type().name()), "action type");
        require(action.type().name().equals(stored.termsActionType()), "action type of the terms");
        require(stored.effectiveAt().equals(action.effectiveAt()), "effective time");
        require(Objects.equals(stored.termsHash(), action.evidenceDigest()), "evidence digest");
        require(ratio(stored.ratioTo(), "ratio.to") == action.numerator(), "split numerator");
        require(ratio(stored.ratioFrom(), "ratio.from") == action.denominator(),
                "split denominator");
    }

    /**
     * {@code terms_document} holds the ratio as {@code {"ratio": {"from": 1, "to": 4}}}: one share
     * before becomes four after. The domain's numerator is the share count after, its denominator
     * the count before.
     */
    private static long ratio(String value, String field) {
        if (value == null) {
            throw conflict("the confirmed corporate action carries no " + field);
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException notANumber) {
            throw conflict("the confirmed corporate action carries a non-numeric " + field);
        }
    }

    private static void require(boolean satisfied, String what) {
        if (!satisfied) {
            throw conflict("the requested " + what
                    + " does not match the confirmed corporate action");
        }
    }

    private List<StoredAdjustment> appliedAdjustments(UUID corporateActionId, UUID botId) {
        return jdbc.sql("""
                        select movement.id, movement.position_lot_id, movement.bot_event_id,
                               lot.flow_id
                        from trading.lot_movements movement
                        join trading.position_lots lot on lot.id = movement.position_lot_id
                        where movement.corporate_action_id = :corporateActionId
                          and movement.bot_id = :botId
                          and movement.movement_type = cast(:movementType
                                                            as trading.lot_movement_type)
                        order by movement.position_lot_id
                        """)
                .param("corporateActionId", corporateActionId)
                .param("botId", botId)
                .param("movementType", MOVEMENT_TYPE)
                .query((resultSet, rowNumber) -> new StoredAdjustment(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("position_lot_id", UUID.class),
                        resultSet.getObject("bot_event_id", UUID.class),
                        resultSet.getObject("flow_id", UUID.class)))
                .list();
    }

    /**
     * Every lot of this bot still holding the instrument, in any of its partitions.
     *
     * <p>A corporate action reaches a whole bot at once, which is why the projections are locked in
     * a single deterministic order: two actions on overlapping instruments cannot interleave into a
     * deadlock, and a concurrent close of one of these lots has to wait rather than race.
     */
    private List<OpenLot> openLots(UUID botId, UUID instrumentId) {
        return jdbc.sql("""
                        select lot.id, lot.partition_id, lot.flow_id,
                               projection.remaining_quantity,
                               projection.remaining_cost_basis_amount,
                               projection.last_event_sequence
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.bot_id = :botId
                          and lot.instrument_id = :instrumentId
                          and projection.remaining_quantity > 0
                        order by lot.opened_at, lot.id
                        for update of projection
                        """)
                .param("botId", botId)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new OpenLot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("partition_id", UUID.class),
                        resultSet.getObject("flow_id", UUID.class),
                        resultSet.getBigDecimal("remaining_quantity"),
                        resultSet.getBigDecimal("remaining_cost_basis_amount"),
                        resultSet.getLong("last_event_sequence")))
                .list();
    }

    /**
     * The append-only record of the adjustment.
     *
     * <p>{@code cost_basis_delta} is zero on purpose: a split re-denominates a holding without
     * changing what it cost, so the basis is carried into {@code cost_basis_after} unchanged and the
     * per-unit cost becomes a derived figure rather than a stored one. The private schema stored
     * {@code adjusted_unit_cost} on its adjustment row; canonical keeps only the two totals it can
     * be recomputed from, and {@code position_lots.unit_cost} deliberately stays at what the lot
     * cost when it opened.
     */
    private void insertMovement(
            CorporateActionApplication application, OpenLot lot, UUID movementId,
            SplitAdjustment adjustment) {
        int inserted = jdbc.sql("""
                        insert into trading.lot_movements (
                            id, bot_id, partition_id, position_lot_id, bot_event_id,
                            corporate_action_id, movement_type, quantity_delta, cost_basis_delta,
                            remaining_after, cost_basis_after, occurred_at
                        ) values (
                            :id, :botId, :partitionId, :positionLotId, :botEventId,
                            :corporateActionId,
                            cast(:movementType as trading.lot_movement_type), :quantityDelta, 0,
                            :remainingAfter, :costBasisAfter, :occurredAt
                        )
                        on conflict do nothing
                        """)
                .param("id", movementId)
                .param("botId", application.botId())
                .param("partitionId", lot.partitionId())
                .param("positionLotId", lot.lotId())
                .param("botEventId", application.botEventId())
                .param("corporateActionId", application.action().actionId())
                .param("movementType", MOVEMENT_TYPE)
                .param("quantityDelta", adjustment.quantityDelta())
                .param("remainingAfter", adjustment.afterQuantity())
                .param("costBasisAfter", adjustment.preservedCostBasis())
                .param("occurredAt", offset(application.action().effectiveAt()))
                .update();
        if (inserted != 1) {
            throw conflict("the lot already moved on this official event");
        }
    }

    /**
     * {@code active_reserved_quantity} is left alone. Reservations own it, and canonical
     * {@code lot_projection_reservation_within_remaining} is what refuses a reverse split that
     * would shrink a lot below what another flow has already reserved out of it.
     */
    private int updateLotProjection(
            OpenLot lot, UUID movementId, SplitAdjustment adjustment, long sequence,
            Instant updatedAt) {
        return jdbc.sql("""
                        update trading.position_lot_projections
                        set remaining_quantity = :remainingQuantity,
                            remaining_cost_basis_amount = :remainingCostBasisAmount,
                            last_movement_id = :lastMovementId,
                            last_event_sequence = :lastEventSequence,
                            updated_at = :updatedAt
                        where position_lot_id = :positionLotId
                          and last_event_sequence = :expectedSequence
                        """)
                .param("remainingQuantity", adjustment.afterQuantity())
                .param("remainingCostBasisAmount", adjustment.preservedCostBasis())
                .param("lastMovementId", movementId)
                .param("lastEventSequence", sequence)
                .param("updatedAt", offset(updatedAt))
                .param("positionLotId", lot.lotId())
                .param("expectedSequence", lot.lastEventSequence())
                .update();
    }

    /**
     * Rebuilds the flow projection from the lots underneath it.
     *
     * <p>Canonical declares the projections rebuildable from {@code position_lots} and
     * {@code lot_movements}, so the split is not applied to the projection a second time: it is
     * summed back out of the lots that were just moved. Applying the ratio to the aggregate instead
     * would have to divide again and could fail on a total that no single lot failed on.
     */
    private void refreshFlow(FlowKey flow, UUID instrumentId, long sequence, Instant updatedAt) {
        long current = jdbc.sql("""
                        select last_event_sequence from trading.flow_position_projections
                        where flow_id = :flowId and instrument_id = :instrumentId
                        for update
                        """)
                .param("flowId", flow.flowId())
                .param("instrumentId", instrumentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> conflict("the adjusted lot has no flow projection"));

        FlowPosition next = jdbc.sql("""
                        select coalesce(sum(case when lot.lot_side = 'LONG'
                                                 then projection.remaining_quantity else 0 end),
                                        0) as long_quantity,
                               coalesce(sum(case when lot.lot_side = 'SHORT'
                                                 then projection.remaining_quantity else 0 end),
                                        0) as short_quantity,
                               coalesce(sum(projection.remaining_cost_basis_amount),
                                        0) as cost_basis_amount
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.flow_id = :flowId and lot.instrument_id = :instrumentId
                        """)
                .param("flowId", flow.flowId())
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new FlowPosition(
                        canonical(resultSet.getBigDecimal("long_quantity")),
                        canonical(resultSet.getBigDecimal("short_quantity")),
                        canonical(resultSet.getBigDecimal("cost_basis_amount")),
                        sequence))
                .single();

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
                .param("projectionHash", flowProjectionHash(flow.flowId(), instrumentId, next))
                .param("updatedAt", offset(updatedAt))
                .param("flowId", flow.flowId())
                .param("instrumentId", instrumentId)
                .param("expectedSequence", current)
                .update();
        if (updated != 1) {
            throw conflict("the flow position changed during the corporate action");
        }
    }

    /**
     * Rebuilds the partition roll-up from the flows underneath it.
     *
     * <p>{@code realized_pnl} is untouched: a split realises nothing. Canonical keeps an average
     * cost rather than a basis on this row, so the average moves even though the basis did not,
     * which is exactly what a split does to a per-unit figure.
     */
    private void refreshPartition(
            UUID partitionId, UUID instrumentId, long sequence, Instant updatedAt) {
        jdbc.sql("""
                        select 1 from trading.partition_position_projections
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        for update
                        """)
                .param("partitionId", partitionId)
                .param("instrumentId", instrumentId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> conflict("the adjusted lot has no partition projection"));

        PartitionTotals totals = jdbc.sql("""
                        select coalesce(sum(long_quantity - short_quantity), 0) as net_quantity,
                               coalesce(sum(cost_basis_amount), 0) as cost_basis_amount
                        from trading.flow_position_projections
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        """)
                .param("partitionId", partitionId)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new PartitionTotals(
                        canonical(resultSet.getBigDecimal("net_quantity")),
                        canonical(resultSet.getBigDecimal("cost_basis_amount"))))
                .single();

        jdbc.sql("""
                        update trading.partition_position_projections
                        set net_quantity = :netQuantity,
                            average_cost = :averageCost,
                            last_bot_event_sequence = :lastBotEventSequence,
                            updated_at = :updatedAt
                        where partition_id = :partitionId and instrument_id = :instrumentId
                        """)
                .param("netQuantity", totals.netQuantity())
                .param("averageCost", totals.averageCost())
                .param("lastBotEventSequence", sequence)
                .param("updatedAt", offset(updatedAt))
                .param("partitionId", partitionId)
                .param("instrumentId", instrumentId)
                .update();
    }

    private long botEventSequence(UUID botId, UUID botEventId) {
        return jdbc.sql("""
                        select event_sequence from bot.bot_events
                        where id = :id and bot_id = :botId
                        """)
                .param("id", botEventId)
                .param("botId", botId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> conflict(
                        "no official event of this bot caused the corporate action"));
    }

    /**
     * Canonical indexes a digest of the flow projection so a rebuilt projection can be compared with
     * the one that was stored.
     *
     * <p>The format is canonical's, not this store's, and the position write path computes the same
     * digest for the same row. It is spelled out again here only because the two write paths are
     * being migrated in separate streams; a single shared helper belongs in one place once they
     * have both landed.
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

    /** A sum of canonical columns is already exact; this only pins the scale it is hashed at. */
    private static BigDecimal canonical(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static CorporateActionConflictException conflict(String message) {
        return new CorporateActionConflictException(message);
    }

    private record CanonicalCorporateAction(
            UUID instrumentId,
            String actionType,
            Instant effectiveAt,
            String termsHash,
            String termsActionType,
            String ratioTo,
            String ratioFrom,
            String manifestStatus,
            boolean superseded) {
    }

    private record StoredAdjustment(
            UUID movementId, UUID positionLotId, UUID botEventId, UUID flowId) {
    }

    private record OpenLot(
            UUID lotId,
            UUID partitionId,
            UUID flowId,
            BigDecimal remainingQuantity,
            BigDecimal remainingCostBasisAmount,
            long lastEventSequence) {
    }

    private record FlowKey(UUID partitionId, UUID flowId) {
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
         * position that is flat, and this path never opens a short, so a partition that is not net
         * long reports no average cost rather than a negative one.
         */
        BigDecimal averageCost() {
            return netQuantity.signum() > 0
                    ? costBasisAmount.divide(netQuantity, SCALE, RoundingMode.HALF_EVEN)
                    : null;
        }
    }
}
