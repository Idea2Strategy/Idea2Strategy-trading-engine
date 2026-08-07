package com.idea2strategy.trading.market.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.trading.messaging.market.DisplayPriceUpdate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class RedisDisplayPricePublisherPayloadTest {
    @Test
    void publishesIntervalOhlcvForAccurateBrowserCandles() {
        DisplayPriceUpdate update = update();

        Map<String, Object> payload = RedisDisplayPricePublisher.payload(update);

        assertEquals(new BigDecimal("100.00"), payload.get("intervalOpen"));
        assertEquals(new BigDecimal("102.00"), payload.get("intervalHigh"));
        assertEquals(new BigDecimal("99.50"), payload.get("intervalLow"));
        assertEquals(new BigDecimal("101.00"), payload.get("intervalClose"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void atomicallyPublishesLatestAndBuildsAOneMinuteBootstrapBar() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.eval(
                        anyString(),
                        eq(ScriptOutputType.INTEGER),
                        any(String[].class),
                        any(String[].class)))
                .thenReturn(1L);
        RedisDisplayPricePublisher publisher = new RedisDisplayPricePublisher(commands, "test", 100);

        publisher.publish(update());

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> arguments = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(
                anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), arguments.capture());
        String instrumentId = "68ed5d6c-7472-44d7-a606-bb1d32726d80";
        assertEquals("{test:market}:display:bars:1m:" + instrumentId, keys.getValue()[1]);
        assertEquals("1786026660", arguments.getValue()[6]);
        assertEquals("100", arguments.getValue()[8]);
    }

    @Test
    void rejectsAKeyPrefixThatCouldEscapeTheSharedRedisClusterSlot() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisDisplayPricePublisher(commands, "bad{prefix}"));
    }

    private static DisplayPriceUpdate update() {
        return new DisplayPriceUpdate(
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
    }
}
