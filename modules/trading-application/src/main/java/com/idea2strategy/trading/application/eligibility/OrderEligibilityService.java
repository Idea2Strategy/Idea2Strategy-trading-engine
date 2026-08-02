package com.idea2strategy.trading.application.eligibility;

import com.idea2strategy.trading.domain.eligibility.OrderEligibilityDecision;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityPolicy;
import com.idea2strategy.trading.domain.eligibility.OrderEligibilityRequest;

public final class OrderEligibilityService {
    private final OrderEligibilityPolicy policy;

    public OrderEligibilityService(OrderEligibilityPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    public OrderEligibilityDecision evaluate(OrderEligibilityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return policy.evaluate(request);
    }
}
