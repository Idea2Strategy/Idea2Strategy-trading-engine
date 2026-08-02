package com.idea2strategy.trading.domain.ledger;

public enum LedgerDirection {
    DEBIT,
    CREDIT;

    public LedgerDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
