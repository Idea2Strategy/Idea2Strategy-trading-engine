package com.idea2strategy.trading.domain.validation;

public enum OrderValidityStatus {
    ACCEPTED,
    REJECTED,
    REEVALUATION_REQUIRED;

    static OrderValidityStatus forReasons(Iterable<OrderValidityReason> reasons) {
        boolean reevaluationRequired = false;
        for (OrderValidityReason reason : reasons) {
            switch (reason) {
                case AVAILABLE_FUNDS_UNAVAILABLE,
                        AVAILABLE_FUNDS_SNAPSHOT_CHANGED,
                        INSUFFICIENT_AVAILABLE_FUNDS -> reevaluationRequired = true;
                case RISK_EVALUATION_UNAVAILABLE,
                        RISK_LIMIT_EXCEEDED,
                        RISK_REDUCTION_NOT_CONFIRMED,
                        INSTRUMENT_POLICY_UNAVAILABLE,
                        MINIMUM_QUANTITY_NOT_MET,
                        MINIMUM_NOTIONAL_NOT_MET,
                        QUANTITY_PRECISION_EXCEEDED,
                        PRICE_PRECISION_EXCEEDED -> {
                    return REJECTED;
                }
            }
        }
        return reevaluationRequired ? REEVALUATION_REQUIRED : ACCEPTED;
    }
}
