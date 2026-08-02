package com.idea2strategy.trading.domain.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One side of a posting, in the shape the canonical ledger can actually hold.
 *
 * <p>The limits below are the canonical columns, not a house style:
 * {@code trading.ledger_accounts.account_type} is {@code varchar(50)} and
 * {@code trading.ledger_entries.amount} is {@code numeric(24,8)}. They are checked here rather
 * than at the database, so an amount the ledger cannot represent is refused where it is readable
 * instead of being silently rounded on the way in.
 */
public record LedgerEntryDraft(String accountCode, LedgerDirection direction, String currency, BigDecimal amount) {
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** {@code trading.ledger_accounts.account_type} is varchar(50). */
    public static final int MAX_ACCOUNT_CODE_LENGTH = 50;

    /** {@code trading.ledger_entries.amount} is numeric(24,8). */
    public static final int AMOUNT_SCALE = 8;

    public static final int AMOUNT_PRECISION = 24;

    public LedgerEntryDraft {
        accountCode = requireText(accountCode, "accountCode");
        if (accountCode.length() > MAX_ACCOUNT_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "accountCode exceeds the canonical account type length of " + MAX_ACCOUNT_CODE_LENGTH);
        }
        direction = Objects.requireNonNull(direction, "direction");
        currency = requireText(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) throw new IllegalArgumentException("currency must match [A-Z]{3}");
        amount = Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        amount = amount.stripTrailingZeros();
        if (amount.scale() < 0) amount = amount.setScale(0);
        if (amount.scale() > AMOUNT_SCALE || amount.precision() - amount.scale() > AMOUNT_PRECISION - AMOUNT_SCALE) {
            throw new IllegalArgumentException(
                    "amount exceeds NUMERIC(" + AMOUNT_PRECISION + "," + AMOUNT_SCALE + ")");
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
