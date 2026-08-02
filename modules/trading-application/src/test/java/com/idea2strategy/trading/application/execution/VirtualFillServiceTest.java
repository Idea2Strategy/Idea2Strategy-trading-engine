package com.idea2strategy.trading.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.application.port.FillDecisionStore;
import com.idea2strategy.trading.domain.execution.FillDecision;
import com.idea2strategy.trading.domain.execution.RealisticFillModel;
import com.idea2strategy.trading.domain.execution.RecordedMarketSnapshot;
import com.idea2strategy.trading.domain.order.OrderLifecycle;
import com.idea2strategy.trading.domain.order.OrderLifecycleFactory;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderTerms;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VirtualFillServiceTest {
    @Test
    void persistsTheProviderNeutralDecisionAndReturnsStoreResult() {
        CapturingStore store = new CapturingStore();
        VirtualFillService service = new VirtualFillService(new RealisticFillModel(), store);
        FillDecision result = service.evaluate(order(), snapshot());
        assertEquals(store.captured, result);
    }

    @Test
    void rejectsNullDependenciesAndInputs() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualFillService(null, decision -> decision));
        assertThrows(IllegalArgumentException.class, () -> new VirtualFillService(new RealisticFillModel(), null));
        VirtualFillService service = new VirtualFillService(new RealisticFillModel(), decision -> decision);
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(null, snapshot()));
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(order(), null));
    }

    private static OrderLifecycle order() {
        return new OrderLifecycleFactory().accepted(new OrderTerms(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000002"), OrderSide.BUY,
                new BigDecimal("2"), OrderType.MARKET, TimeInForce.DAY, null, null, null, null),
                Instant.parse("2026-08-02T14:29:00Z"));
    }

    private static RecordedMarketSnapshot snapshot() {
        return new RecordedMarketSnapshot(UUID.fromString("40000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-02T14:30:00Z"), new BigDecimal("99"), new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("2"), null);
    }

    private static final class CapturingStore implements FillDecisionStore {
        private FillDecision captured;
        @Override public FillDecision createOrLoad(FillDecision decision) { return captured = decision; }
    }
}
