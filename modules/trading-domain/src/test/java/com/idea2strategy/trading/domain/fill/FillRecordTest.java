package com.idea2strategy.trading.domain.fill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FillRecordTest {
    private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-02T14:30:00Z");

    @Test
    void eachProviderExecutionCreatesOneDeterministicTradeIdentity() {
        FillRecord first = FillRecord.original(ORDER_ID, "alpaca-fill-1", new BigDecimal("0.25"),
                new BigDecimal("100.05"), new BigDecimal("0.050025"), new BigDecimal("0.0125"), T0, T0);
        FillRecord retry = FillRecord.original(ORDER_ID, "alpaca-fill-1", new BigDecimal("0.250"),
                new BigDecimal("100.0500"), new BigDecimal("0.0500250"), new BigDecimal("0.01250"), T0, T0);
        FillRecord nextPartial = FillRecord.original(ORDER_ID, "alpaca-fill-2", new BigDecimal("0.75"),
                new BigDecimal("100.06"), new BigDecimal("0.15009"), new BigDecimal("0.045"), T0.plusSeconds(1), T0.plusSeconds(1));

        assertEquals(first, retry);
        assertNotEquals(first.rootFillId(), nextPartial.rootFillId());
        assertEquals(0, first.revision());
        assertEquals(FillRecordKind.ORIGINAL, first.kind());
    }

    @Test
    void correctionsAndBustsAppendARevisionWithoutReplacingTheOriginal() {
        FillRecord original = original();
        FillRecord correction = original.corrected(new BigDecimal("0.2"), new BigDecimal("100.04"),
                new BigDecimal("0.040016"), new BigDecimal("0.008"), T0.plusSeconds(2), T0.plusSeconds(3));
        FillRecord bust = correction.busted(T0.plusSeconds(4), T0.plusSeconds(5));

        assertEquals(original.rootFillId(), correction.rootFillId());
        assertEquals(original.fillRecordId(), correction.correctionOfRecordId());
        assertEquals(correction.fillRecordId(), bust.correctionOfRecordId());
        assertEquals(1, correction.revision());
        assertEquals(2, bust.revision());
        assertEquals(BigDecimal.ZERO, bust.quantity());
        assertThrows(IllegalArgumentException.class, () -> new FillRecord(
                correction.fillRecordId(), correction.requestFingerprint(), correction.rootFillId(),
                original.fillRecordId(), ORDER_ID, "alpaca-fill-1", 2, FillRecordKind.CORRECTION,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, T0, T0));
    }

    private static FillRecord original() {
        return FillRecord.original(ORDER_ID, "alpaca-fill-1", new BigDecimal("0.25"),
                new BigDecimal("100.05"), new BigDecimal("0.050025"), new BigDecimal("0.0125"), T0, T0);
    }
}
