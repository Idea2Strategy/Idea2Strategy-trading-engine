package com.idea2strategy.trading.domain.shorting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OpenShortLotSnapshot(
        UUID lotId,
        UUID instrumentId,
        UUID botId,
        BigDecimal openQuantity,
        BigDecimal referencePrice,
        BigDecimal annualBorrowRate,
        String rateSnapshotVersion,
        Instant openedAt) {
    public OpenShortLotSnapshot {
        lotId = ShortInputs.required(lotId, "lotId");
        instrumentId = ShortInputs.required(instrumentId, "instrumentId");
        botId = ShortInputs.required(botId, "botId");
        openQuantity = ShortInputs.canonical(ShortInputs.positive(openQuantity, "openQuantity"));
        referencePrice = ShortInputs.canonical(ShortInputs.positive(referencePrice, "referencePrice"));
        annualBorrowRate = ShortInputs.canonical(ShortInputs.nonNegative(annualBorrowRate, "annualBorrowRate"));
        rateSnapshotVersion = ShortInputs.text(rateSnapshotVersion, "rateSnapshotVersion");
        openedAt = ShortInputs.required(openedAt, "openedAt");
    }
}
