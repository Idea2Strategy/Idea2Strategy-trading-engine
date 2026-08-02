package com.idea2strategy.trading.persistence.shorting;

import com.idea2strategy.trading.application.port.BorrowFeeAccrualStore;
import com.idea2strategy.trading.application.shorting.BorrowFeeAccrualConflictException;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresBorrowFeeAccrualStore implements BorrowFeeAccrualStore {
    private final JdbcClient jdbc;

    public PostgresBorrowFeeAccrualStore(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Override
    public BorrowFeeAccrual appendOrLoad(BorrowFeeAccrual desired) {
        Objects.requireNonNull(desired, "desired");
        int inserted = jdbc.sql("""
                insert into trading.short_borrow_fee_accrual (
                    accrual_id, lot_id, instrument_id, bot_id, accrual_date, open_quantity, reference_price,
                    notional_amount, annual_borrow_rate, fee_amount, currency, day_count_convention,
                    policy_version, rate_snapshot_version, accrued_at
                ) values (:id, :lotId, :instrumentId, :botId, :date, :quantity, :price,
                    :notional, :rate, :fee, :currency, :dayCount, :policy, :rateVersion, :at)
                on conflict do nothing
                """).param("id", desired.accrualId()).param("lotId", desired.lotId())
                .param("instrumentId", desired.instrumentId()).param("botId", desired.botId())
                .param("date", desired.accrualDate()).param("quantity", desired.openQuantity())
                .param("price", desired.referencePrice()).param("notional", desired.notionalAmount())
                .param("rate", desired.annualBorrowRate()).param("fee", desired.feeAmount())
                .param("currency", desired.currency()).param("dayCount", desired.dayCountConvention().name())
                .param("policy", desired.policyVersion()).param("rateVersion", desired.rateSnapshotVersion())
                .param("at", OffsetDateTime.ofInstant(desired.accruedAt(), ZoneOffset.UTC)).update();
        BorrowFeeAccrual stored = inserted == 1 ? loadById(desired.accrualId())
                : loadByLotAndDate(desired.lotId(), desired.accrualDate());
        if (!stored.equals(desired)) {
            throw new BorrowFeeAccrualConflictException("accrual identity already exists with different evidence");
        }
        return stored;
    }

    private BorrowFeeAccrual loadById(java.util.UUID id) {
        return query("where accrual_id = :id").param("id", id).query(this::map).single();
    }

    private BorrowFeeAccrual loadByLotAndDate(java.util.UUID lotId, java.time.LocalDate date) {
        return query("where lot_id = :lotId and accrual_date = :date")
                .param("lotId", lotId).param("date", date).query(this::map).single();
    }

    private JdbcClient.StatementSpec query(String predicate) {
        return jdbc.sql("""
                select accrual_id, lot_id, instrument_id, bot_id, accrual_date, open_quantity, reference_price,
                    notional_amount, annual_borrow_rate, fee_amount, currency, day_count_convention,
                    policy_version, rate_snapshot_version, accrued_at
                from trading.short_borrow_fee_accrual
                """ + predicate);
    }

    private BorrowFeeAccrual map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BorrowFeeAccrual(
                rs.getObject("accrual_id", java.util.UUID.class), rs.getObject("lot_id", java.util.UUID.class),
                rs.getObject("instrument_id", java.util.UUID.class), rs.getObject("bot_id", java.util.UUID.class),
                rs.getObject("accrual_date", java.time.LocalDate.class), rs.getBigDecimal("open_quantity"),
                rs.getBigDecimal("reference_price"), rs.getBigDecimal("notional_amount"),
                rs.getBigDecimal("annual_borrow_rate"), rs.getBigDecimal("fee_amount"), rs.getString("currency"),
                com.idea2strategy.trading.domain.shorting.DayCountConvention.valueOf(rs.getString("day_count_convention")),
                rs.getString("policy_version"), rs.getString("rate_snapshot_version"),
                rs.getObject("accrued_at", OffsetDateTime.class).toInstant());
    }
}
