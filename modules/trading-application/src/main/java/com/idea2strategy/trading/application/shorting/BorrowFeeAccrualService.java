package com.idea2strategy.trading.application.shorting;

import com.idea2strategy.trading.application.port.BorrowFeeAccrualStore;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;
import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrualRequest;
import java.util.Objects;

public final class BorrowFeeAccrualService {
    private final BorrowFeeAccrualStore store;

    public BorrowFeeAccrualService(BorrowFeeAccrualStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public BorrowFeeAccrual accrue(BorrowFeeAccrualRequest request) {
        return store.appendOrLoad(BorrowFeeAccrual.calculate(request));
    }
}
