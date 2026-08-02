package com.idea2strategy.trading.application.port;

import com.idea2strategy.trading.application.position.CloseLongPositionCommand;
import com.idea2strategy.trading.application.position.OpenPositionLotCommand;
import com.idea2strategy.trading.application.position.PositionMutationResult;

public interface PositionLotStore {
    PositionMutationResult open(OpenPositionLotCommand command);
    PositionMutationResult closeLong(CloseLongPositionCommand command);
}
