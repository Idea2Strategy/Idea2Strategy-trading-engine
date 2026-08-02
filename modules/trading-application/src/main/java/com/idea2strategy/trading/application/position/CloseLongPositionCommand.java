package com.idea2strategy.trading.application.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CloseLongPositionCommand(UUID botId, UUID partitionId, UUID flowId, UUID instrumentId,
                                       UUID fillRecordId, BigDecimal quantity, BigDecimal price,
                                       BigDecimal commission, Instant occurredAt) {
    public CloseLongPositionCommand {
        if (botId==null||partitionId==null||flowId==null||instrumentId==null||fillRecordId==null
                ||quantity==null||price==null||commission==null||occurredAt==null) throw new IllegalArgumentException("close values must not be null");
        if(quantity.signum()<=0||price.signum()<=0||commission.signum()<0) throw new IllegalArgumentException("close numeric values are invalid");
    }
}
