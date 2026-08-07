package com.idea2strategy.trading.market.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedisDisplayPricePublisherPayloadTest {
    @Test
    void publishesIntervalOhlcvForAccurateBrowserCandles() {
        DisplayPriceUpdate update = new DisplayPriceUpdate(
                UUID.fromString("68ed5d6c-7472-44d7-a606-bb1d32726d80"),
                "AAPL",
                new BigDecimal("101.00"),
                new BigDecimal("2"),
                new BigDecimal("100.00"),
                new BigDecimal("102.00"),
                new BigDecimal("99.50"),
                new BigDecimal("101.00"),
                new BigDecimal("8"),
                4,
                42,
                Instant.parse("2026-08-06T14:31:10.100Z"),
                Instant.parse("2026-08-06T14:31:10.250Z"));

        Map<String, Object> payload = RedisDisplayPricePublisher.payload(update);

        assertEquals(new BigDecimal("100.00"), payload.get("intervalOpen"));
        assertEquals(new BigDecimal("102.00"), payload.get("intervalHigh"));
        assertEquals(new BigDecimal("99.50"), payload.get("intervalLow"));
        assertEquals(new BigDecimal("101.00"), payload.get("intervalClose"));
    }
}
