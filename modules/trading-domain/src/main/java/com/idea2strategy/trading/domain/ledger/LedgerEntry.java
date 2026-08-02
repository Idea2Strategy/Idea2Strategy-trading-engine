package com.idea2strategy.trading.domain.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntry(
        UUID entryId,
        int sequence,
        String accountCode,
        LedgerDirection direction,
        String currency,
        BigDecimal amount,
        UUID sourceEventId) {
    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        LedgerEntryDraft validated = new LedgerEntryDraft(accountCode, direction, currency, amount);
        accountCode = validated.accountCode();
        direction = validated.direction();
        currency = validated.currency();
        amount = validated.amount();
        Objects.requireNonNull(sourceEventId, "sourceEventId");
    }

    LedgerEntryDraft toDraft() {
        return new LedgerEntryDraft(accountCode, direction, currency, amount);
    }
}
