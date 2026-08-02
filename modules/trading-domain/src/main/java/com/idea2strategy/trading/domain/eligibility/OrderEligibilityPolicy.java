package com.idea2strategy.trading.domain.eligibility;

import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_INSTRUMENT_NOT_ENABLED;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_REQUIRES_LONG_EXPOSURE;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.FRACTIONAL_REQUIRES_MARKET_DAY;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityReason.WHOLE_SHARES_REQUIRE_INTEGER_QUANTITY;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityStatus.ACCEPTED;
import static com.idea2strategy.trading.domain.eligibility.OrderEligibilityStatus.REJECTED;
import static com.idea2strategy.trading.domain.order.OrderType.MARKET;
import static com.idea2strategy.trading.domain.order.TimeInForce.DAY;

import java.util.ArrayList;
import java.util.List;

public final class OrderEligibilityPolicy {

    public OrderEligibilityDecision evaluate(OrderEligibilityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        List<OrderEligibilityReason> reasons = new ArrayList<>();
        if (request.quantityMode() == QuantityMode.WHOLE_SHARES) {
            if (request.requestedValue().stripTrailingZeros().scale() > 0) {
                reasons.add(WHOLE_SHARES_REQUIRE_INTEGER_QUANTITY);
            }
        } else {
            if (!request.instrumentPolicy().fractionalEnabled()) {
                reasons.add(FRACTIONAL_INSTRUMENT_NOT_ENABLED);
            }
            if (request.positionEffect() != OrderPositionEffect.INCREASE_LONG
                    && request.positionEffect() != OrderPositionEffect.REDUCE_LONG) {
                reasons.add(FRACTIONAL_REQUIRES_LONG_EXPOSURE);
            }
            if (request.orderType() != MARKET || request.timeInForce() != DAY) {
                reasons.add(FRACTIONAL_REQUIRES_MARKET_DAY);
            }
        }

        return new OrderEligibilityDecision(
                request,
                reasons.isEmpty() ? ACCEPTED : REJECTED,
                reasons.isEmpty() ? List.of() : List.copyOf(reasons));
    }
}
