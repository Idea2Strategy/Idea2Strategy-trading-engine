package com.idea2strategy.trading.domain.eligibility;

import java.util.List;

public record OrderEligibilityDecision(
        OrderEligibilityRequest request,
        OrderEligibilityStatus status,
        List<OrderEligibilityReason> reasons) {

    public OrderEligibilityDecision {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (reasons == null || reasons.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("reasons must not contain null");
        }
        reasons = List.copyOf(reasons);
        for (int index = 1; index < reasons.size(); index++) {
            if (reasons.get(index - 1).compareTo(reasons.get(index)) >= 0) {
                throw new IllegalArgumentException("reasons must be sorted without duplicates");
            }
        }
        if (status == OrderEligibilityStatus.ACCEPTED && !reasons.isEmpty()) {
            throw new IllegalArgumentException("accepted decision must not have reasons");
        }
        if (status == OrderEligibilityStatus.REJECTED && reasons.isEmpty()) {
            throw new IllegalArgumentException("rejected decision requires reasons");
        }
    }
}
