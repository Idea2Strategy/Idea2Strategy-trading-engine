package com.idea2strategy.trading.domain.order;

import java.util.Objects;
import java.util.UUID;

/**
 * The trading isolation boundary an order belongs to.
 *
 * <p>Every canonical order row and everything hanging off it carries {@code (bot_id, partition_id)}
 * as a composite foreign key, because conflict resolution, netting and resource locking all happen
 * inside one partition. The scope comes from the intent the order was composed from; an order can
 * never span two partitions.
 */
public record OrderScope(UUID botId, UUID partitionId) {

    public OrderScope {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(partitionId, "partitionId");
    }
}
