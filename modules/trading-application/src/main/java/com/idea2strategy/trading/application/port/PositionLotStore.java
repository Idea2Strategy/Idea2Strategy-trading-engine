package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.position.PositionMutationResult;
import com.idea2strategy.trading.domain.position.LotClosing;
import com.idea2strategy.trading.domain.position.LotOpening;

public interface PositionLotStore {

    /** Opens the lot this fill allocation bought, or returns the lot it already opened. */
    PositionMutationResult open(LotOpening opening);

    /** Consumes long lots in FIFO order for this closing fill allocation. */
    PositionMutationResult closeLong(LotClosing closing);
}
