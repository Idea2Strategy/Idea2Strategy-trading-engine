package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketStreamHealthTrackerTest {
    @Test
    void requestsOnlyTheExactMissingRestRangeAndTracksRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        List<RecoveryRequest> requests = new ArrayList<>();
        MarketStreamHealthTracker tracker = new MarketStreamHealthTracker(
                clock,
                Duration.ofSeconds(5),
                (symbol, range) -> requests.add(new RecoveryRequest(symbol, range)));

        assertEquals(MarketStreamStatus.FRESH, tracker.record("aapl", 10, clock.instant()));
        assertEquals(MarketStreamStatus.GAP, tracker.record("AAPL", 14, clock.instant()));
        assertEquals(
                List.of(new RecoveryRequest("AAPL", new MissingSequenceRange(11, 13))),
                requests);

        tracker.markRecovered("AAPL", 14, clock.instant());
        assertEquals(MarketStreamStatus.FRESH, tracker.status("AAPL"));
    }

    @Test
    void marksAQuietStreamStaleWithoutCreatingARestRecoveryRequest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        List<RecoveryRequest> requests = new ArrayList<>();
        MarketStreamHealthTracker tracker = new MarketStreamHealthTracker(
                clock,
                Duration.ofSeconds(5),
                (symbol, range) -> requests.add(new RecoveryRequest(symbol, range)));

        tracker.record("MSFT", 1, clock.instant());
        clock.advance(Duration.ofSeconds(6));

        assertEquals(MarketStreamStatus.STALE, tracker.status("MSFT"));
        assertEquals(List.of(), requests);
    }

    private record RecoveryRequest(String symbol, MissingSequenceRange range) {}

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
