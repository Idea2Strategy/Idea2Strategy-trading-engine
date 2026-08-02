package com.idea2strategy.trading.application.eligibility;

import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityStatus.ACCEPTED;
import static com.idea2strategy.trading.domain.eligibility.OrderPositionEffect.INCREASE_LONG;
import static com.idea2strategy.trading.domain.eligibility.QuantityMode.FRACTIONAL_SHARES;
import static com.idea2strategy.trading.domain.order.OrderType.MARKET;
import static com.idea2strategy.trading.domain.order.TimeInForce.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.domain.eligibility.InstrumentFractionalPolicy;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityPolicy;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEligibilityServiceTest {
    @Test
    void exposesEligibilityEvaluationAsApplicationBoundary() {
        OrderEligibilityService service = new OrderEligibilityService(new OrderEligibilityPolicy());
        var request = new OrderEligibilityRequest(
                new InstrumentFractionalPolicy(
                        UUID.fromString("30000000-0000-0000-0000-000000000003"), true, "alpaca-v1"),
                INCREASE_LONG,
                MARKET,
                DAY,
                FRACTIONAL_SHARES,
                new BigDecimal("0.3330"));

        var result = service.evaluate(request);

        assertEquals(ACCEPTED, result.status());
        assertEquals("0.3330", result.request().requestedValue().toPlainString());
    }

    @Test
    void rejectsMissingPolicyAndRequestInsteadOfApplyingDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEligibilityService(null));
        OrderEligibilityService service = new OrderEligibilityService(new OrderEligibilityPolicy());
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(null));
    }
}
