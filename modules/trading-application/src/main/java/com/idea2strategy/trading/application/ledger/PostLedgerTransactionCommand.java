package com.idea2strategy.trading.application.ledger;

import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.util.Objects;
import java.util.UUID;

public record PostLedgerTransactionCommand(UUID commandId, LedgerTransaction transaction) {
    public PostLedgerTransactionCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(transaction, "transaction");
    }
}
