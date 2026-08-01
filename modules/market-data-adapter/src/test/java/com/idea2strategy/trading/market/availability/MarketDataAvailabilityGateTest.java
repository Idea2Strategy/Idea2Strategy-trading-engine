package com.idea2strategy.trading.market.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.market.alpaca.MarketStreamStatus;
import com.idea2strategy.trading.market.redis.ConsumerLagMeasurement;
import com.idea2strategy.trading.market.session.MarketSessionAssessment;
import com.idea2strategy.trading.market.session.MarketSessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketDataAvailabilityGateTest {
    private static final Instant NOW = Instant.parse("2026-08-03T14:00:00Z");

    @Test
    void emitsOneDegradedAndOneRecoveredEventAcrossRepeatedChecks() {
        MarketDataAvailabilityGate gate = gate();

        MarketDataAvailabilityResult degraded = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.GAP, lag(0, Duration.ZERO), regularOpen()));
        MarketDataAvailabilityResult repeated = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.GAP, lag(0, Duration.ZERO), regularOpen()));
        MarketDataAvailabilityResult recovered = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.FRESH, lag(0, Duration.ZERO), regularOpen()));
        MarketDataAvailabilityResult healthyAgain = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.FRESH, lag(0, Duration.ZERO), regularOpen()));

        assertEquals(MarketDataAvailabilityStatus.DEGRADED, degraded.status());
        assertFalse(degraded.evaluationAllowed());
        assertFalse(degraded.orderCandidateAllowed());
        assertEquals(Set.of(MarketDataDegradationReason.SEQUENCE_GAP), degraded.reasons());
        assertEquals(MarketDataAvailabilityEventType.DATA_DEGRADED, degraded.events().getFirst().type());
        assertTrue(repeated.events().isEmpty());
        assertEquals(MarketDataAvailabilityStatus.AVAILABLE, recovered.status());
        assertEquals(MarketDataAvailabilityEventType.DATA_RECOVERED, recovered.events().getFirst().type());
        assertTrue(recovered.evaluationAllowed());
        assertTrue(recovered.orderCandidateAllowed());
        assertTrue(healthyAgain.events().isEmpty());
    }

    @Test
    void waitsForEveryCauseToClearBeforeRecovery() {
        MarketDataAvailabilityGate gate = gate();
        gate.evaluate(input("AAPL", false, MarketStreamStatus.GAP, lag(0, Duration.ZERO), regularOpen()));

        MarketDataAvailabilityResult partial = gate.evaluate(input(
                "AAPL", false, MarketStreamStatus.FRESH, lag(0, Duration.ZERO), regularOpen()));
        MarketDataAvailabilityResult recovered = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.FRESH, lag(0, Duration.ZERO), regularOpen()));

        assertEquals(Set.of(MarketDataDegradationReason.PROVIDER_DISCONNECTED), partial.reasons());
        assertTrue(partial.events().isEmpty());
        assertEquals(MarketDataAvailabilityEventType.DATA_RECOVERED, recovered.events().getFirst().type());
    }

    @Test
    void blocksStaleStreamsExcessLagAndUnavailableCalendars() {
        MarketDataAvailabilityGate gate = gate();

        MarketDataAvailabilityResult result = gate.evaluate(input(
                "MSFT",
                true,
                MarketStreamStatus.STALE,
                lag(11, Duration.ofSeconds(3)),
                unavailableCalendar()));

        assertEquals(
                Set.of(
                        MarketDataDegradationReason.STREAM_STALE,
                        MarketDataDegradationReason.CONSUMER_LAG,
                        MarketDataDegradationReason.CALENDAR_UNAVAILABLE),
                result.reasons());
        assertEquals(1, result.events().size());
    }

    @Test
    void treatsHealthyRegularMarketClosureSeparatelyFromDataDegradation() {
        MarketDataAvailabilityGate gate = gate();

        MarketDataAvailabilityResult result = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.STALE, lag(100, Duration.ofMinutes(1)), marketClosed()));

        assertEquals(MarketDataAvailabilityStatus.MARKET_CLOSED, result.status());
        assertTrue(result.reasons().isEmpty());
        assertTrue(result.events().isEmpty());
        assertFalse(result.evaluationAllowed());
    }

    @Test
    void appliesLagThresholdsAndStateIndependentlyPerSymbol() {
        MarketDataAvailabilityGate gate = gate();
        MarketDataAvailabilityResult aapl = gate.evaluate(input(
                "AAPL", true, MarketStreamStatus.FRESH, lag(10, Duration.ofSeconds(2)), regularOpen()));
        MarketDataAvailabilityResult msft = gate.evaluate(input(
                "MSFT", true, MarketStreamStatus.FRESH, lag(11, Duration.ofSeconds(2)), regularOpen()));

        assertEquals(MarketDataAvailabilityStatus.AVAILABLE, aapl.status());
        assertEquals(MarketDataAvailabilityStatus.DEGRADED, msft.status());
        assertTrue(gate.evaluate(input(
                        "AAPL", true, MarketStreamStatus.FRESH, lag(0, Duration.ZERO), regularOpen()))
                .events()
                .isEmpty());
    }

    private static MarketDataAvailabilityGate gate() {
        return new MarketDataAvailabilityGate(10, Duration.ofSeconds(2));
    }

    private static MarketDataAvailabilityInput input(
            String symbol,
            boolean providerConnected,
            MarketStreamStatus streamStatus,
            ConsumerLagMeasurement lag,
            MarketSessionAssessment session) {
        return new MarketDataAvailabilityInput(symbol, providerConnected, streamStatus, lag, session, NOW);
    }

    private static ConsumerLagMeasurement lag(long entries, Duration duration) {
        return new ConsumerLagMeasurement(entries, duration, "1-0");
    }

    private static MarketSessionAssessment regularOpen() {
        return new MarketSessionAssessment(
                MarketSessionStatus.REGULAR_OPEN, true, "open", Optional.empty());
    }

    private static MarketSessionAssessment marketClosed() {
        return new MarketSessionAssessment(
                MarketSessionStatus.MARKET_CLOSED, false, "closed", Optional.empty());
    }

    private static MarketSessionAssessment unavailableCalendar() {
        return new MarketSessionAssessment(
                MarketSessionStatus.CALENDAR_UNAVAILABLE, false, "unavailable", Optional.empty());
    }
}
