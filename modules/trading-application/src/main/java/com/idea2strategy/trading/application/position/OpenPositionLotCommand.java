package com.idea2strategy.trading.application.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OpenPositionLotCommand(UUID botId, UUID partitionId, UUID flowId, UUID instrumentId,
                                     UUID fillRecordId, BigDecimal quantity, BigDecimal price,
                                     BigDecimal commission, Instant occurredAt) {
    public OpenPositionLotCommand {
        required(botId, "botId"); required(partitionId, "partitionId"); required(flowId, "flowId");
        required(instrumentId, "instrumentId"); required(fillRecordId, "fillRecordId");
        positive(quantity, "quantity"); positive(price, "price"); nonNegative(commission, "commission");
        required(occurredAt, "occurredAt");
    }
    private static void positive(BigDecimal v, String n) { nonNegative(v,n); if(v.signum()<=0) throw new IllegalArgumentException(n+" must be positive"); }
    private static void nonNegative(BigDecimal v, String n) { required(v,n); if(v.signum()<0) throw new IllegalArgumentException(n+" must not be negative"); }
    private static <T> T required(T v,String n){if(v==null)throw new IllegalArgumentException(n+" must not be null");return v;}
}
