package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.ledger.PostLedgerTransactionCommand;
import com.idea2strategy.trading.domain.ledger.LedgerTransaction;

public interface LedgerStore {
    LedgerTransaction append(PostLedgerTransactionCommand command);
}
