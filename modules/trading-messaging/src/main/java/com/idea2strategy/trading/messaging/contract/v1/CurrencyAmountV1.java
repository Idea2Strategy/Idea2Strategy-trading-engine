package com.idea2strategy.trading.messaging.contract.v1;

public record CurrencyAmountV1(String currency, DecimalValueV1 amount) {
    public CurrencyAmountV1 {
        ContractValidationV1.currencyCode(currency, "currency");
        ContractValidationV1.required(amount, "amount");
    }
}
