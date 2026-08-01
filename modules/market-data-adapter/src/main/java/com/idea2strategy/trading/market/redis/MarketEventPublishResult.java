package com.idea2strategy.trading.market.redis;

import java.util.Objects;

public record MarketEventPublishResult(
        MarketEventPublishStatus status,
        String streamEntryId,
        boolean latestUpdated) {

    public MarketEventPublishResult {
        status = Objects.requireNonNull(status, "status");
        streamEntryId = Objects.requireNonNull(streamEntryId, "streamEntryId");
        if (status == MarketEventPublishStatus.PUBLISHED && streamEntryId.isBlank()) {
            throw new IllegalArgumentException("a published event requires a stream entry ID");
        }
        if (status != MarketEventPublishStatus.PUBLISHED && !streamEntryId.isEmpty()) {
            throw new IllegalArgumentException("an unpublished event cannot have a stream entry ID");
        }
        if (latestUpdated && status != MarketEventPublishStatus.PUBLISHED) {
            throw new IllegalArgumentException("latest state cannot update without publication");
        }
    }

    public static MarketEventPublishResult skipped() {
        return new MarketEventPublishResult(MarketEventPublishStatus.SKIPPED, "", false);
    }
}
