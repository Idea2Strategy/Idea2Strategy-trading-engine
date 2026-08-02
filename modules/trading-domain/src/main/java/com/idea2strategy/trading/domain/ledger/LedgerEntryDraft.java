package com.idea2strategy.trading.domain.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

public record LedgerEntryDraft(String accountCode, LedgerDirection direction, String currency, BigDecimal amount) {
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public LedgerEntryDraft {
        accountCode = requireText(accountCode, "accountCode");
        direction = Objects.requireNonNull(direction, "direction");
        currency = requireText(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) throw new IllegalArgumentException("currency must match [A-Z]{3}");
        amount = Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        amount = amount.stripTrailingZeros();
        if (amount.scale() < 0) amount = amount.setScale(0);
        if (amount.scale() > 18 || amount.precision() > 38) {
            throw new IllegalArgumentException("amount exceeds NUMERIC(38,18) precision");
        }
    }

    public static LedgerEntryDraft debit(String accountCode, String currency, BigDecimal amount) {
        return new LedgerEntryDraft(accountCode, LedgerDirection.DEBIT, currency, amount);
    }

    public static LedgerEntryDraft credit(String accountCode, String currency, BigDecimal amount) {
        return new LedgerEntryDraft(accountCode, LedgerDirection.CREDIT, currency, amount);
    }

    public LedgerEntryDraft opposite() {
        return new LedgerEntryDraft(accountCode, direction.opposite(), currency, amount);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
