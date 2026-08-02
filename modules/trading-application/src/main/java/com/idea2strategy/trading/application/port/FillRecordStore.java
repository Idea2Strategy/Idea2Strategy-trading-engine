package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.domain.fill.FillRecord;

public interface FillRecordStore {
    FillRecord appendOrLoad(FillRecord desired);
}
