package com.idea2strategy.trading.persistence.shorting;

import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;
import com.idea2strategy.trading.domain.shorting.DayCountConvention;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BorrowFeeAccrualPersistenceView(
        UUID accrualId, UUID lotId, UUID instrumentId, UUID botId, LocalDate accrualDate,
        BigDecimal openQuantity, BigDecimal referencePrice, BigDecimal notionalAmount,
        BigDecimal annualBorrowRate, BigDecimal feeAmount, String currency, String dayCountConvention,
        String policyVersion, String rateSnapshotVersion, Instant accruedAt) {
    public BorrowFeeAccrual toDomain() {
        return new BorrowFeeAccrual(accrualId, lotId, instrumentId, botId, accrualDate, openQuantity,
                referencePrice, notionalAmount, annualBorrowRate, feeAmount, currency,
                DayCountConvention.valueOf(dayCountConvention), policyVersion, rateSnapshotVersion, accruedAt);
    }
}
