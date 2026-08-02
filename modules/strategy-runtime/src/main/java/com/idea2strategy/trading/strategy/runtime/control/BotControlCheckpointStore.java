package com.idea2strategy.trading.strategy.runtime.control;

import java.util.Optional;
import java.util.UUID;

public interface BotControlCheckpointStore {
    Optional<BotControlCheckpoint> find(UUID botId);

    void save(BotControlCheckpoint checkpoint);
}
