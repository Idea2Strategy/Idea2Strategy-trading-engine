package com.idea2strategy.trading.market.alpaca;

import com.idea2strategy.trading.messaging.market.MarketEventEnvelope;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MarketEventOrderingProcessor {
    private final Map<StreamKey, StreamState> streams = new HashMap<>();

    public synchronized MarketEventHandlingResult process(MarketEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        StreamState state = streams.computeIfAbsent(StreamKey.from(event), ignored -> new StreamState());

        if (state.seenEventIds.contains(event.eventId())) {
            return result(MarketEventHandlingStatus.DUPLICATE, event, state, false, false);
        }
        if (event.revision() > 0) {
            return processCorrection(event, state);
        }
        return processOriginal(event, state);
    }

    private static MarketEventHandlingResult processOriginal(
            MarketEventEnvelope event,
            StreamState state) {
        if (state.latestSequence >= 0 && event.sequence() < state.latestSequence) {
            rememberOriginal(event, state);
            return result(MarketEventHandlingStatus.OUT_OF_ORDER, event, state, true, false);
        }
        if (state.latestSequence >= 0 && event.sequence() == state.latestSequence) {
            state.seenEventIds.add(event.eventId());
            return result(MarketEventHandlingStatus.SEQUENCE_CONFLICT, event, state, false, false);
        }

        state.latestSequence = event.sequence();
        rememberOriginal(event, state);
        return result(MarketEventHandlingStatus.APPLIED, event, state, true, true);
    }

    private static MarketEventHandlingResult processCorrection(
            MarketEventEnvelope event,
            StreamState state) {
        String originalEventId = event.correctionOfEventId();
        Integer latestRevision = state.latestRevisionByOriginalEventId.get(originalEventId);
        if (latestRevision == null) {
            return result(MarketEventHandlingStatus.ORPHAN_CORRECTION, event, state, false, false);
        }

        state.seenEventIds.add(event.eventId());
        if (event.revision() <= latestRevision) {
            return result(MarketEventHandlingStatus.STALE_CORRECTION, event, state, false, false);
        }

        state.latestRevisionByOriginalEventId.put(originalEventId, event.revision());
        boolean updatesLatest = event.sequence() == state.latestSequence;
        return result(MarketEventHandlingStatus.CORRECTION_APPLIED, event, state, true, updatesLatest);
    }

    private static void rememberOriginal(MarketEventEnvelope event, StreamState state) {
        state.seenEventIds.add(event.eventId());
        state.latestRevisionByOriginalEventId.putIfAbsent(event.eventId(), 0);
    }

    private static MarketEventHandlingResult result(
            MarketEventHandlingStatus status,
            MarketEventEnvelope event,
            StreamState state,
            boolean shouldPublish,
            boolean shouldUpdateLatestValue) {
        return new MarketEventHandlingResult(
                status,
                event,
                state.latestSequence,
                shouldPublish,
                shouldUpdateLatestValue);
    }

    private record StreamKey(
            String provider,
            String feed,
            UUID instrumentId,
            MarketEventType eventType) {
        private static StreamKey from(MarketEventEnvelope event) {
            return new StreamKey(event.provider(), event.feed(), event.instrumentId(), event.eventType());
        }
    }

    private static final class StreamState {
        private long latestSequence = -1;
        private final Set<String> seenEventIds = new HashSet<>();
        private final Map<String, Integer> latestRevisionByOriginalEventId = new HashMap<>();
    }
}
