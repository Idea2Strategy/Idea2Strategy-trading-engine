package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.shorting.BorrowFeeAccrual;

public interface BorrowFeeAccrualStore {
    BorrowFeeAccrual appendOrLoad(BorrowFeeAccrual accrual);
}
