package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FinalizedCandleBoundaryPlanner {
    public List<Instant> readyBoundaries(
            OfficialMarketSession session,
            Instant now,
            Duration finalizationGrace) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(finalizationGrace, "finalizationGrace");
        if (finalizationGrace.isNegative()) {
            throw new IllegalArgumentException("finalizationGrace must not be negative");
        }
        Instant finalizedThrough = now.minus(finalizationGrace);
        if (!finalizedThrough.isAfter(session.opensAt())) {
            return List.of();
        }
        Instant last = finalizedThrough.isBefore(session.closesAt()) ? finalizedThrough : session.closesAt();
        List<Instant> result = new ArrayList<>();
        for (Instant boundary = session.opensAt().plus(Duration.ofMinutes(30));
                !boundary.isAfter(last);
                boundary = boundary.plus(Duration.ofMinutes(30))) {
            result.add(boundary);
        }
        return List.copyOf(result);
    }
}
