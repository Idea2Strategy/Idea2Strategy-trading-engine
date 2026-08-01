package com.idea2strategy.trading.messaging.contract.v1;

import java.math.BigDecimal;

public record DecimalValueV1(String value) {
    public DecimalValueV1 {
        ContractValidationV1.canonicalNonNegativeDecimal(value, "decimal");
    }

    public BigDecimal asBigDecimal() {
        return new BigDecimal(value);
    }
}
