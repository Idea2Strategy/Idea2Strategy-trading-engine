package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {
    @Test
    void doublesDelayAndStopsAtTheConfiguredMaximum() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(1), backoff.delayForAttempt(1));
        assertEquals(Duration.ofSeconds(2), backoff.delayForAttempt(2));
        assertEquals(Duration.ofSeconds(16), backoff.delayForAttempt(5));
        assertEquals(Duration.ofSeconds(30), backoff.delayForAttempt(6));
        assertEquals(Duration.ofSeconds(30), backoff.delayForAttempt(100));
    }
}
