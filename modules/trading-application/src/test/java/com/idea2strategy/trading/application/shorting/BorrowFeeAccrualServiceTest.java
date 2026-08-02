package com.idea2strategy.trading.application.shorting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.application.port.BorrowFeeAccrualStore;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrualRequest;
import com.idea2strategy.trading.domain.shorting.BorrowFeePolicy;
import com.idea2strategy.trading.domain.shorting.DayCountConvention;
import com.idea2strategy.trading.domain.shorting.OpenShortLotSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BorrowFeeAccrualServiceTest {
    @Test
    void calculatesThenPersistsThroughTheApplicationPort() {
        List<BorrowFeeAccrual> appended = new ArrayList<>();
        BorrowFeeAccrualStore store = accrual -> { appended.add(accrual); return accrual; };
        BorrowFeeAccrualService service = new BorrowFeeAccrualService(store);

        BorrowFeeAccrual result = service.accrue(request());

        assertEquals(List.of(result), appended);
    }

    private static BorrowFeeAccrualRequest request() {
        OpenShortLotSnapshot lot = new OpenShortLotSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, new BigDecimal("20"), new BigDecimal("0.04"), "rate-v1",
                Instant.parse("2026-07-01T00:00:00Z"));
        return new BorrowFeeAccrualRequest(lot, LocalDate.parse("2026-08-01"),
                Instant.parse("2026-08-02T00:00:00Z"), new BorrowFeePolicy(
                "policy-v1", DayCountConvention.ACTUAL_360, 4, RoundingMode.HALF_UP, "USD"));
    }
}
