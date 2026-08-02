package com.idea2strategy.trading.persistence.shorting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqBorrowFeeAccrualQuery {
    private static final String COLUMNS = """
            accrual_id, lot_id, instrument_id, bot_id, accrual_date, open_quantity, reference_price,
            notional_amount, annual_borrow_rate, fee_amount, currency, day_count_convention,
            policy_version, rate_snapshot_version, accrued_at
            """;
    private final DSLContext dsl;

    public JooqBorrowFeeAccrualQuery(DSLContext dsl) { this.dsl = java.util.Objects.requireNonNull(dsl, "dsl"); }

    public Optional<BorrowFeeAccrualPersistenceView> findById(UUID id) {
        return dsl.fetchOptional("select " + COLUMNS + " from trading.short_borrow_fee_accrual where accrual_id = ?", id)
                .map(this::map);
    }

    public List<BorrowFeeAccrualPersistenceView> findByLot(UUID lotId) {
        return dsl.fetch("select " + COLUMNS + " from trading.short_borrow_fee_accrual "
                        + "where lot_id = ? order by accrual_date, accrued_at, accrual_id", lotId)
                .map(this::map);
    }

    private BorrowFeeAccrualPersistenceView map(Record row) {
        return new BorrowFeeAccrualPersistenceView(
                row.get("accrual_id", UUID.class), row.get("lot_id", UUID.class),
                row.get("instrument_id", UUID.class), row.get("bot_id", UUID.class),
                row.get("accrual_date", java.time.LocalDate.class), row.get("open_quantity", java.math.BigDecimal.class),
                row.get("reference_price", java.math.BigDecimal.class), row.get("notional_amount", java.math.BigDecimal.class),
                row.get("annual_borrow_rate", java.math.BigDecimal.class), row.get("fee_amount", java.math.BigDecimal.class),
                row.get("currency", String.class), row.get("day_count_convention", String.class),
                row.get("policy_version", String.class), row.get("rate_snapshot_version", String.class),
                row.get("accrued_at", OffsetDateTime.class).toInstant());
    }
}
