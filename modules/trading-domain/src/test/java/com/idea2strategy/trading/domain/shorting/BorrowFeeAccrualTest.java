package com.idea2strategy.trading.domain.shorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BorrowFeeAccrualTest {
    @Test
    void calculatesOneDeterministicDailyAccrualFromExplicitLotAndPolicyEvidence() {
        UUID lotId = UUID.randomUUID();
        BorrowFeeAccrualRequest request = request(lotId, LocalDate.parse("2026-08-01"));

        BorrowFeeAccrual first = BorrowFeeAccrual.calculate(request);
        BorrowFeeAccrual retry = BorrowFeeAccrual.calculate(request);

        assertEquals(first, retry);
        assertEquals(new BigDecimal("0.1389"), first.feeAmount());
        assertEquals(new BigDecimal("1000"), first.notionalAmount());
        assertEquals("ACTUAL_360", first.dayCountConvention().name());
        assertEquals("borrow-policy-v3", first.policyVersion());
        assertEquals("rate-snapshot-v17", first.rateSnapshotVersion());
        assertNotEquals(first.accrualId(), BorrowFeeAccrual.calculate(
                request(lotId, LocalDate.parse("2026-08-02"))).accrualId());
    }

    @Test
    void rejectsMissingVersionAndNonPositiveOpenShortQuantity() {
        BorrowFeePolicy policy = new BorrowFeePolicy(
                "borrow-policy-v3", DayCountConvention.ACTUAL_360, 4, RoundingMode.HALF_UP, "USD");
        assertThrows(IllegalArgumentException.class, () -> new OpenShortLotSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO,
                new BigDecimal("50"), new BigDecimal("0.05"), "rate-v1", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new BorrowFeePolicy(
                "", DayCountConvention.ACTUAL_360, 4, RoundingMode.HALF_UP, "USD"));
    }

    private static BorrowFeeAccrualRequest request(UUID lotId, LocalDate date) {
        OpenShortLotSnapshot lot = new OpenShortLotSnapshot(lotId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("20"), new BigDecimal("50"), new BigDecimal("0.05"),
                "rate-snapshot-v17", Instant.parse("2026-07-01T14:30:00Z"));
        BorrowFeePolicy policy = new BorrowFeePolicy(
                "borrow-policy-v3", DayCountConvention.ACTUAL_360, 4, RoundingMode.HALF_UP, "USD");
        return new BorrowFeeAccrualRequest(lot, date, Instant.parse(date.plusDays(1) + "T00:00:00Z"), policy);
    }
}
