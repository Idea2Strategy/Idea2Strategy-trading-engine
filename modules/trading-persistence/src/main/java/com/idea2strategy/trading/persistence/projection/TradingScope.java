package com.idea2strategy.trading.persistence.projection;

import java.util.Objects;
import java.util.UUID;

/**
 * Ownership and scope of a canonical trading read.
 *
 * <p>The canonical model already carries {@code bot_id}, {@code partition_id} and {@code flow_id}
 * on the official records, so this type only names the requested scope. It never becomes a second
 * source of truth for attribution.
 */
public record TradingScope(UUID ownerAccountId, UUID botId, UUID partitionId, UUID flowId) {

    public TradingScope {
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(botId, "botId");
        if (flowId != null && partitionId == null) {
            throw new IllegalArgumentException("flow scope requires a partition");
        }
    }

    public static TradingScope ofBot(UUID ownerAccountId, UUID botId) {
        return new TradingScope(ownerAccountId, botId, null, null);
    }

    public static TradingScope ofPartition(UUID ownerAccountId, UUID botId, UUID partitionId) {
        return new TradingScope(
                ownerAccountId, botId, Objects.requireNonNull(partitionId, "partitionId"), null);
    }

    public static TradingScope ofFlow(
            UUID ownerAccountId, UUID botId, UUID partitionId, UUID flowId) {
        return new TradingScope(
                ownerAccountId,
                botId,
                Objects.requireNonNull(partitionId, "partitionId"),
                Objects.requireNonNull(flowId, "flowId"));
    }

    UUID requirePartitionId() {
        if (partitionId == null) {
            throw new IllegalArgumentException("this projection requires a partition scope");
        }
        return partitionId;
    }

    UUID requireFlowId() {
        if (flowId == null) {
            throw new IllegalArgumentException("this projection requires a flow scope");
        }
        return flowId;
    }
}
