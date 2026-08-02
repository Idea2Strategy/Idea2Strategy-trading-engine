package com.idea2strategy.trading.domain.shorting;

import java.time.Instant;
import java.time.LocalDate;

public record BorrowFeeAccrualRequest(
        OpenShortLotSnapshot lot,
        LocalDate accrualDate,
        Instant accruedAt,
        BorrowFeePolicy policy) {
    public BorrowFeeAccrualRequest {
        lot = ShortInputs.required(lot, "lot");
        accrualDate = ShortInputs.required(accrualDate, "accrualDate");
        accruedAt = ShortInputs.required(accruedAt, "accruedAt");
        policy = ShortInputs.required(policy, "policy");
        if (accruedAt.isBefore(lot.openedAt())) throw new IllegalArgumentException("accruedAt precedes lot opening");
    }
}
