package com.idea2strategy.trading.persistence.budget;

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
 * Reads the canonical budget projection tables through the jOOQ query boundary.
 *
 * <p>Columns come back as canonical holds them rather than as a domain round-trip, so a caller
 * checking that a rebuild landed sees the stored row and not a re-derivation of it. That matters
 * more here than elsewhere: these tables are the derived answer, so a read that recomputed anything
 * could not tell a correct row from a wrong one.
 */
@Repository
public class JooqBudgetProjectionQuery {

    private final DSLContext dsl;

    public JooqBudgetProjectionQuery(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public Optional<BotBudgetRowView> findBot(UUID botId) {
        return dsl.fetchOptional("""
                        select bot_id, currency_code, available_cash_amount,
                               active_reservation_amount, invested_amount,
                               segregated_short_proceeds_amount, short_collateral_amount,
                               valuation_at, valuation_status, last_event_sequence,
                               projection_hash, updated_at
                        from trading.bot_budget_projections
                        where bot_id = ?
                        """, Objects.requireNonNull(botId, "botId"))
                .map(JooqBudgetProjectionQuery::toBot);
    }

    public Optional<PartitionBudgetRowView> findPartition(UUID partitionId) {
        return dsl.fetchOptional("""
                        select partition_id, bot_id, currency_code, budget_cap_amount,
                               active_reservation_amount, invested_amount,
                               segregated_short_proceeds_amount, short_collateral_amount,
                               valuation_at, valuation_status, last_event_sequence,
                               projection_hash, updated_at
                        from trading.partition_budget_projections
                        where partition_id = ?
                        """, Objects.requireNonNull(partitionId, "partitionId"))
                .map(JooqBudgetProjectionQuery::toPartition);
    }

    /** Every partition budget row of one bot, in the order canonical's unique index keys them. */
    public List<PartitionBudgetRowView> findPartitionsOfBot(UUID botId) {
        return dsl.fetch("""
                        select partition_id, bot_id, currency_code, budget_cap_amount,
                               active_reservation_amount, invested_amount,
                               segregated_short_proceeds_amount, short_collateral_amount,
                               valuation_at, valuation_status, last_event_sequence,
                               projection_hash, updated_at
                        from trading.partition_budget_projections
                        where bot_id = ?
                        order by partition_id
                        """, Objects.requireNonNull(botId, "botId"))
                .map(JooqBudgetProjectionQuery::toPartition);
    }

    public int countBotProjections() {
        return dsl.fetchCount(dsl.selectFrom("trading.bot_budget_projections"));
    }

    public int countPartitionProjections() {
        return dsl.fetchCount(dsl.selectFrom("trading.partition_budget_projections"));
    }

    private static BotBudgetRowView toBot(Record record) {
        return new BotBudgetRowView(
                record.get("bot_id", UUID.class),
                record.get("currency_code", String.class),
                record.get("available_cash_amount", BigDecimal.class),
                record.get("active_reservation_amount", BigDecimal.class),
                record.get("invested_amount", BigDecimal.class),
                record.get("segregated_short_proceeds_amount", BigDecimal.class),
                record.get("short_collateral_amount", BigDecimal.class),
                instant(record, "valuation_at"),
                record.get("valuation_status", String.class),
                record.get("last_event_sequence", Long.class),
                record.get("projection_hash", String.class),
                instant(record, "updated_at"));
    }

    private static PartitionBudgetRowView toPartition(Record record) {
        return new PartitionBudgetRowView(
                record.get("partition_id", UUID.class),
                record.get("bot_id", UUID.class),
                record.get("currency_code", String.class),
                record.get("budget_cap_amount", BigDecimal.class),
                record.get("active_reservation_amount", BigDecimal.class),
                record.get("invested_amount", BigDecimal.class),
                record.get("segregated_short_proceeds_amount", BigDecimal.class),
                record.get("short_collateral_amount", BigDecimal.class),
                instant(record, "valuation_at"),
                record.get("valuation_status", String.class),
                record.get("last_event_sequence", Long.class),
                record.get("projection_hash", String.class),
                instant(record, "updated_at"));
    }

    private static Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record BotBudgetRowView(
            UUID botId, String currencyCode, BigDecimal availableCashAmount,
            BigDecimal activeReservationAmount, BigDecimal investedAmount,
            BigDecimal segregatedShortProceedsAmount, BigDecimal shortCollateralAmount,
            Instant valuationAt, String valuationStatus, long lastEventSequence,
            String projectionHash, Instant updatedAt) {
    }

    public record PartitionBudgetRowView(
            UUID partitionId, UUID botId, String currencyCode, BigDecimal budgetCapAmount,
            BigDecimal activeReservationAmount, BigDecimal investedAmount,
            BigDecimal segregatedShortProceedsAmount, BigDecimal shortCollateralAmount,
            Instant valuationAt, String valuationStatus, long lastEventSequence,
            String projectionHash, Instant updatedAt) {
    }
}
