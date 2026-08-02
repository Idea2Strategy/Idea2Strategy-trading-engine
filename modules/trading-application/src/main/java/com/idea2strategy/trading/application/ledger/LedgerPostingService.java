package com.idea2strategy.trading.application.ledger;

import com.idea2strategy.trading.application.port.LedgerStore;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;
import java.util.Objects;

public final class LedgerPostingService {
    private final LedgerStore store;

    public LedgerPostingService(LedgerStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public LedgerTransaction post(PostLedgerTransactionCommand command) {
        return store.append(Objects.requireNonNull(command, "command"));
    }
}
