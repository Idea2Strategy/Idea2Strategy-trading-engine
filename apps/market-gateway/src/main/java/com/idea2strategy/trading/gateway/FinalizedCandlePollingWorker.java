package com.idea2strategy.trading.gateway;

import com.idea2strategy.trading.market.candle.FinalizedCandleBoundaryPlanner;
import com.idea2strategy.trading.market.candle.FinalizedCandleCycle;
import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.market.session.OfficialMarketSessionEvaluator;
import com.idea2strategy.trading.market.session.OfficialMarketSessionSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls only official 30m boundaries; Redis event IDs make restart catch-up idempotent. */
final class FinalizedCandlePollingWorker {
    private static final Logger log = LoggerFactory.getLogger(FinalizedCandlePollingWorker.class);

    private final FinalizedCandleCycle cycle;
    private final ApprovedInstruments instruments;
    private final OfficialMarketSessionSource sessions;
    private final Clock clock;
    private final Duration grace;
    private final FinalizedCandleBoundaryPlanner planner = new FinalizedCandleBoundaryPlanner();
    private final Set<String> completed = new HashSet<>();
    private final Map<String, Set<String>> pendingSymbols = new LinkedHashMap<>();

    FinalizedCandlePollingWorker(
            FinalizedCandleCycle cycle,
            ApprovedInstruments instruments,
            OfficialMarketSessionSource sessions,
            Clock clock,
            Duration grace) {
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grace = Objects.requireNonNull(grace, "grace");
    }

    @Scheduled(fixedDelayString = "${market-gateway.candle-poll-delay:PT5S}")
    public void poll() {
        Instant now = clock.instant();
        LocalDate tradingDate = now.atZone(OfficialMarketSessionEvaluator.NEW_YORK).toLocalDate();
        String currentDatePrefix = tradingDate + ":";
        completed.removeIf(key -> !key.startsWith(currentDatePrefix));
        pendingSymbols.keySet().removeIf(key -> !key.startsWith(currentDatePrefix));
        OfficialMarketSession session = sessions.session(tradingDate).orElse(null);
        if (session == null) {
            return;
        }
        for (Instant boundary : planner.readyBoundaries(session, now, grace)) {
            String key = tradingDate + ":" + boundary;
            if (completed.contains(key)) {
                continue;
            }
            try {
                Map<String, java.util.UUID> requested = requestedInstruments(key);
                var result = cycle.run(requested, session, boundary);
                if (result.missingSymbols().isEmpty()) {
                    pendingSymbols.remove(key);
                    completed.add(key);
                } else {
                    pendingSymbols.put(key, result.missingSymbols());
                }
                log.info("finalized 30m boundary {}: evaluated={}, missing={}",
                        boundary, result.evaluatedInstrumentCount(), result.missingInstrumentCount());
            } catch (RuntimeException failure) {
                log.error("finalized 30m boundary {} failed and will be retried", boundary, failure);
                return;
            }
        }
    }

    private Map<String, java.util.UUID> requestedInstruments(String boundaryKey) {
        Set<String> pending = pendingSymbols.get(boundaryKey);
        if (pending == null) {
            return instruments.bySymbol();
        }
        Map<String, java.util.UUID> requested = new LinkedHashMap<>();
        pending.forEach(symbol -> {
            java.util.UUID instrumentId = instruments.bySymbol().get(symbol);
            if (instrumentId != null) {
                requested.put(symbol, instrumentId);
            }
        });
        return Map.copyOf(requested);
    }
}
