package com.idea2strategy.trading.application.position;

import com.idea2strategy.trading.application.port.PositionLotStore;
import com.idea2strategy.trading.domain.position.LotClosing;
import com.idea2strategy.trading.domain.position.LotOpening;
import java.util.Objects;

public final class PositionLotService {

    private final PositionLotStore store;

    public PositionLotService(PositionLotStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public PositionMutationResult open(LotOpening opening) {
        return store.open(opening);
    }

    public PositionMutationResult closeLong(LotClosing closing) {
        return store.closeLong(closing);
    }
}
