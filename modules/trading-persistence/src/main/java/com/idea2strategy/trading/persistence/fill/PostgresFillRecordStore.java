package com.idea2strategy.trading.persistence.fill;

import com.idea2strategy.trading.application.fill.FillRecordConflictException;
import com.idea2strategy.trading.application.port.FillRecordStore;
import com.idea2strategy.trading.domain.fill.FillAllocation;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;
import com.idea2strategy.trading.domain.fill.FillRecordKind;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the canonical fill tables.
 *
 * <p>The private schema kept every revision of a fill as another row in one table. Canonical splits
 * that: {@code trading.fills} holds the original, and a later correction or bust is a
 * {@code trading.fill_adjustments} row carrying the deltas that move the effective quantity. The
 * original is never rewritten, which is what keeps the evidence of what was first reported.
 *
 * <p>{@code trading.fill_component_allocations} attributes the fill back to the order components it
 * settles. Canonical checks at commit that the allocations sum exactly to the fill, and separately
 * that the order projection matches the sum of its fills, so this store is only ever correct as part
 * of a transaction that also advances the order.
 */
@Repository
public class PostgresFillRecordStore implements FillRecordStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresFillRecordStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public FillRecord appendOrLoad(FillPosting posting) {
        Objects.requireNonNull(posting, "posting");
        return transaction.execute(status -> appendInTransaction(posting));
    }

    private FillRecord appendInTransaction(FillPosting posting) {
        FillRecord desired = posting.record();
        return desired.revision() == 0 ? appendOriginal(posting) : appendAdjustment(posting);
    }

    private FillRecord appendOriginal(FillPosting posting) {
        FillRecord desired = posting.record();
        int inserted = jdbc.sql("""
                        insert into trading.fills (
                            id, order_id, bot_id, partition_id, bot_event_id, provider_fill_key,
                            quantity, reference_price, reference_observed_at, reference_market_hash,
                            slippage_rate_bps, slippage_amount, fill_price, gross_amount,
                            fee_policy_id, fee_rate_bps, precision_rules_version, fee_basis_amount,
                            fee_amount, settlement_cash_delta, occurred_at, recorded_at
                        ) values (
                            :id, :orderId, :botId, :partitionId, :botEventId, :providerFillKey,
                            :quantity, :referencePrice, :referenceObservedAt, :referenceMarketHash,
                            :slippageRateBps, :slippageAmount, :fillPrice, :grossAmount,
                            :feePolicyId, :feeRateBps, :precisionRulesVersion, :feeBasisAmount,
                            :feeAmount, :settlementCashDelta, :occurredAt, :recordedAt
                        )
                        on conflict do nothing
                        """)
                .param("id", desired.fillRecordId())
                .param("orderId", desired.orderId())
                .param("botId", posting.scope().botId())
                .param("partitionId", posting.scope().partitionId())
                .param("botEventId", posting.botEventId())
                .param("providerFillKey", desired.sourceExecutionId())
                .param("quantity", desired.quantity())
                .param("referencePrice", posting.referencePrice())
                .param("referenceObservedAt", offset(posting.referenceObservedAt()))
                .param("referenceMarketHash", posting.referenceMarketHash())
                .param("slippageRateBps", FillPosting.FIXED_SLIPPAGE_RATE_BPS)
                .param("slippageAmount", desired.slippage())
                .param("fillPrice", desired.price())
                .param("grossAmount", posting.grossAmount())
                .param("feePolicyId", posting.feePolicyId())
                .param("feeRateBps", posting.feeRateBps())
                .param("precisionRulesVersion", posting.precisionRulesVersion())
                .param("feeBasisAmount", posting.feeBasisAmount())
                .param("feeAmount", desired.commission())
                .param("settlementCashDelta", posting.settlementCashDelta())
                .param("occurredAt", offset(desired.occurredAt()))
                .param("recordedAt", offset(desired.receivedAt()))
                .update();

        if (inserted != 1) {
            return replayOriginal(posting);
        }
        for (FillAllocation allocation : posting.allocations()) {
            requireOne(insertAllocation(posting, allocation));
        }
        return desired;
    }

    /**
     * A correction or bust never rewrites the original. Canonical records the difference, and a bust
     * is the special case where the difference exactly negates it; {@code one_reversal_per_fill}
     * makes that possible only once.
     */
    private FillRecord appendAdjustment(FillPosting posting) {
        FillRecord desired = posting.record();
        StoredEconomics original = loadEconomics(desired.rootFillId())
                .orElseThrow(() -> conflict("an adjustment arrived before the original fill"));

        // Canonical assert_fill_adjustment requires a reversal to negate the original exactly, so the
        // deltas come from what was stored rather than from what the caller reports now.
        BigDecimal quantityDelta = original.quantity().negate();
        BigDecimal grossDelta = original.grossAmount().negate();
        BigDecimal feeDelta = original.feeAmount().negate();
        BigDecimal cashDelta = original.settlementCashDelta().negate();

        int inserted = jdbc.sql("""
                        insert into trading.fill_adjustments (
                            id, bot_id, partition_id, fill_id, bot_event_id, adjustment_key,
                            adjustment_type, quantity_delta, gross_amount_delta, fee_amount_delta,
                            settlement_cash_delta, reason_code, occurred_at
                        ) values (
                            :id, :botId, :partitionId, :fillId, :botEventId, :adjustmentKey,
                            cast(:adjustmentType as trading.fill_adjustment_type), :quantityDelta,
                            :grossAmountDelta, :feeAmountDelta, :settlementCashDelta, :reasonCode,
                            :occurredAt
                        )
                        on conflict do nothing
                        """)
                .param("id", desired.fillRecordId())
                .param("botId", posting.scope().botId())
                .param("partitionId", posting.scope().partitionId())
                .param("fillId", desired.rootFillId())
                .param("botEventId", posting.botEventId())
                .param("adjustmentKey", desired.sourceExecutionId() + ":r" + desired.revision())
                .param("adjustmentType", adjustmentType(desired.kind()))
                .param("quantityDelta", quantityDelta)
                .param("grossAmountDelta", grossDelta)
                .param("feeAmountDelta", feeDelta)
                .param("settlementCashDelta", cashDelta)
                .param("reasonCode", desired.kind().name())
                .param("occurredAt", offset(desired.occurredAt()))
                .update();

        if (inserted != 1) {
            return loadAdjustedRecord(desired).orElseThrow(() -> conflict("fill adjustment conflict"));
        }
        return desired;
    }

    private static String adjustmentType(FillRecordKind kind) {
        return switch (kind) {
            case BUST -> "REVERSAL";
            case CORRECTION -> "CORRECTION";
            case ORIGINAL -> throw new IllegalArgumentException("an original is not an adjustment");
        };
    }

    private int insertAllocation(FillPosting posting, FillAllocation allocation) {
        return jdbc.sql("""
                        insert into trading.fill_component_allocations (
                            id, bot_id, partition_id, order_id, fill_id, order_component_id,
                            allocation_sequence, allocated_quantity, allocated_gross_amount,
                            allocated_fee_amount, allocated_settlement_cash_delta,
                            allocation_rules_version
                        ) values (
                            gen_random_uuid(), :botId, :partitionId, :orderId, :fillId,
                            :orderComponentId, :allocationSequence, :allocatedQuantity,
                            :allocatedGrossAmount, :allocatedFeeAmount,
                            :allocatedSettlementCashDelta, :allocationRulesVersion
                        )
                        on conflict do nothing
                        """)
                .param("botId", posting.scope().botId())
                .param("partitionId", posting.scope().partitionId())
                .param("orderId", posting.record().orderId())
                .param("fillId", posting.record().fillRecordId())
                .param("orderComponentId", allocation.orderComponentId())
                .param("allocationSequence", allocation.allocationSequence())
                .param("allocatedQuantity", allocation.allocatedQuantity())
                .param("allocatedGrossAmount", allocation.allocatedGrossAmount())
                .param("allocatedFeeAmount", allocation.allocatedFeeAmount())
                .param("allocatedSettlementCashDelta", allocation.allocatedSettlementCashDelta())
                .param("allocationRulesVersion", posting.allocationRulesVersion())
                .update();
    }

    private FillRecord replayOriginal(FillPosting posting) {
        FillRecord stored = loadOriginal(posting.record().fillRecordId())
                .orElseThrow(() -> conflict("fill record identity conflict"));
        if (!stored.requestFingerprint().equals(posting.record().requestFingerprint())) {
            throw conflict("fill record identity conflict");
        }
        return stored;
    }

    /**
     * Rebuilds the domain record from the canonical fill. The fingerprint is derived from the same
     * payload the record hashes, so a stored fill that no longer matches its reported economics is
     * refused by {@link FillRecord} itself rather than silently accepted here.
     */
    private Optional<FillRecord> loadOriginal(UUID fillId) {
        return jdbc.sql("""
                        select id, order_id, provider_fill_key, quantity, fill_price, fee_amount,
                               slippage_amount, occurred_at, recorded_at
                        from trading.fills
                        where id = :id
                        """)
                .param("id", fillId)
                .query((resultSet, rowNumber) -> toOriginal(resultSet))
                .optional();
    }

    private static FillRecord toOriginal(ResultSet resultSet) throws SQLException {
        return FillRecord.original(
                resultSet.getObject("order_id", UUID.class),
                resultSet.getString("provider_fill_key"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("fill_price"),
                resultSet.getBigDecimal("fee_amount"),
                resultSet.getBigDecimal("slippage_amount"),
                instant(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                instant(resultSet.getObject("recorded_at", OffsetDateTime.class)));
    }

    private Optional<FillRecord> loadAdjustedRecord(FillRecord desired) {
        Integer stored = jdbc.sql("select 1 from trading.fill_adjustments where id = :id")
                .param("id", desired.fillRecordId())
                .query(Integer.class)
                .optional()
                .orElse(null);
        return stored == null ? Optional.empty() : Optional.of(desired);
    }

    private Optional<StoredEconomics> loadEconomics(UUID fillId) {
        return jdbc.sql("""
                        select quantity, gross_amount, fee_amount, settlement_cash_delta
                        from trading.fills
                        where id = :id
                        """)
                .param("id", fillId)
                .query((resultSet, rowNumber) -> new StoredEconomics(
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("gross_amount"),
                        resultSet.getBigDecimal("fee_amount"),
                        resultSet.getBigDecimal("settlement_cash_delta")))
                .optional();
    }

    private record StoredEconomics(
            BigDecimal quantity,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal settlementCashDelta) {
    }

    private static void requireOne(int inserted) {
        if (inserted != 1) {
            throw conflict("fill allocation conflict");
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static FillRecordConflictException conflict(String message) {
        return new FillRecordConflictException(message);
    }
}
