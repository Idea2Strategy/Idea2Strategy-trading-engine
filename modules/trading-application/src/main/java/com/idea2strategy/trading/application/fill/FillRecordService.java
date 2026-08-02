package com.idea2strategy.trading.application.fill;

import com.idea2strategy.trading.application.port.FillRecordStore;
import com.idea2strategy.trading.domain.fill.FillPosting;
import com.idea2strategy.trading.domain.fill.FillRecord;

public final class FillRecordService {
    private final FillRecordStore store;

    public FillRecordService(FillRecordStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
    }

    public FillRecord record(FillPosting posting) {
        if (posting == null) throw new IllegalArgumentException("posting must not be null");
        FillRecord result = store.appendOrLoad(posting);
        if (result == null) throw new IllegalStateException("store returned null");
        return result;
    }
}
