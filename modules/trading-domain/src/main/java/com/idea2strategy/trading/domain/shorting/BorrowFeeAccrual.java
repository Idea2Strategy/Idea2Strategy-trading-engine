package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BorrowFeeAccrual(
        UUID accrualId,
        UUID lotId,
        UUID instrumentId,
        UUID botId,
        LocalDate accrualDate,
        BigDecimal openQuantity,
        BigDecimal referencePrice,
        BigDecimal notionalAmount,
        BigDecimal annualBorrowRate,
        BigDecimal feeAmount,
        String currency,
        DayCountConvention dayCountConvention,
        String policyVersion,
        String rateSnapshotVersion,
        Instant accruedAt) {

    public static BorrowFeeAccrual calculate(BorrowFeeAccrualRequest request) {
        ShortInputs.required(request, "request");
        var lot = request.lot();
        var policy = request.policy();
        BigDecimal notional = lot.openQuantity().multiply(lot.referencePrice());
        BigDecimal fee = notional.multiply(lot.annualBorrowRate())
                .divide(policy.dayCountConvention().daysPerYear(), policy.currencyScale(), policy.roundingMode());
        String identity = lot.lotId() + "|" + request.accrualDate();
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new BorrowFeeAccrual(id, lot.lotId(), lot.instrumentId(), lot.botId(), request.accrualDate(),
                lot.openQuantity(), lot.referencePrice(), notional, lot.annualBorrowRate(), fee, policy.currency(),
                policy.dayCountConvention(), policy.policyVersion(), lot.rateSnapshotVersion(), request.accruedAt());
    }

    public BorrowFeeAccrual {
        accrualId = ShortInputs.required(accrualId, "accrualId");
        lotId = ShortInputs.required(lotId, "lotId");
        instrumentId = ShortInputs.required(instrumentId, "instrumentId");
        botId = ShortInputs.required(botId, "botId");
        accrualDate = ShortInputs.required(accrualDate, "accrualDate");
        openQuantity = ShortInputs.canonical(ShortInputs.positive(openQuantity, "openQuantity"));
        referencePrice = ShortInputs.canonical(ShortInputs.positive(referencePrice, "referencePrice"));
        notionalAmount = ShortInputs.canonical(ShortInputs.positive(notionalAmount, "notionalAmount"));
        annualBorrowRate = ShortInputs.canonical(ShortInputs.nonNegative(annualBorrowRate, "annualBorrowRate"));
        feeAmount = ShortInputs.canonical(ShortInputs.nonNegative(feeAmount, "feeAmount"));
        currency = ShortInputs.text(currency, "currency");
        dayCountConvention = ShortInputs.required(dayCountConvention, "dayCountConvention");
        policyVersion = ShortInputs.text(policyVersion, "policyVersion");
        rateSnapshotVersion = ShortInputs.text(rateSnapshotVersion, "rateSnapshotVersion");
        accruedAt = ShortInputs.required(accruedAt, "accruedAt");
    }
}
