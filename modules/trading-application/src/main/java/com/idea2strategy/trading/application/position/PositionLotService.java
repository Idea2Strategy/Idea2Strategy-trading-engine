package com.idea2strategy.trading.application.position;

import com.idea2strategy.trading.application.port.PositionLotStore;

public final class PositionLotService {
    private final PositionLotStore store;
    public PositionLotService(PositionLotStore store){if(store==null)throw new IllegalArgumentException("store must not be null");this.store=store;}
    public PositionMutationResult open(OpenPositionLotCommand command){return store.open(command);}
    public PositionMutationResult closeLong(CloseLongPositionCommand command){return store.closeLong(command);}
}
