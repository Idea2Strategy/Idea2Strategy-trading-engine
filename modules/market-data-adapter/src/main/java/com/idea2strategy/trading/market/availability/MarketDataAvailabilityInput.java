package com.idea2strategy.trading.market.availability;

import com.idea2strategy.trading.market.alpaca.MarketStreamStatus;
import com.idea2strategy.trading.market.redis.ConsumerLagMeasurement;
import com.idea2strategy.trading.market.session.MarketSessionAssessment;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record MarketDataAvailabilityInput(
        String symbol,
        boolean providerConnected,
        MarketStreamStatus streamStatus,
        ConsumerLagMeasurement consumerLag,
        MarketSessionAssessment sessionAssessment,
        Instant observedAt) {

    public MarketDataAvailabilityInput {
        symbol = normalizeSymbol(symbol);
        streamStatus = Objects.requireNonNull(streamStatus, "streamStatus");
        consumerLag = Objects.requireNonNull(consumerLag, "consumerLag");
        sessionAssessment = Objects.requireNonNull(sessionAssessment, "sessionAssessment");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
