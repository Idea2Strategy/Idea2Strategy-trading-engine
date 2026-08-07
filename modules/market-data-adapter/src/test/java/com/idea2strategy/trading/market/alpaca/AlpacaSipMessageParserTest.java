package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpacaSipMessageParserTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-31T14:31:00.050Z");
    private final AlpacaSipMessageParser parser = new AlpacaSipMessageParser();

    @Test
    void parsesTheOfficialControlFrameSequenceForTradeSubscriptions() {
        assertEquals(List.of(new AlpacaSipInboundMessage.Connected()),
                parser.parse("[{\"T\":\"success\",\"msg\":\"connected\"}]", RECEIVED_AT));
        assertEquals(List.of(new AlpacaSipInboundMessage.Authenticated()),
                parser.parse("[{\"T\":\"success\",\"msg\":\"authenticated\"}]", RECEIVED_AT));
        assertEquals(
                List.of(new AlpacaSipInboundMessage.SubscriptionConfirmed(List.of("AAPL", "MSFT"))),
                parser.parse("[{\"T\":\"subscription\",\"trades\":[\"AAPL\",\"MSFT\"],"
                        + "\"quotes\":[],\"bars\":[]}]", RECEIVED_AT));
    }

    @Test
    void parsesTradeTicksWithoutTurningThemIntoStrategyEvents() {
        List<AlpacaSipInboundMessage> messages = parser.parse(
                "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":529835250,\"x\":\"V\","
                        + "\"p\":210.125,\"s\":20,\"c\":[\"@\"],"
                        + "\"t\":\"2026-07-31T14:30:00.123456Z\",\"z\":\"C\"}]",
                RECEIVED_AT);

        assertEquals(List.of(new AlpacaSipInboundMessage.TradeTick(
                "AAPL", 529835250L, "V", new BigDecimal("210.125"), new BigDecimal("20"),
                Instant.parse("2026-07-31T14:30:00.123456Z"), RECEIVED_AT, List.of("@"), "C")), messages);
    }

    @Test
    void oneMinuteBarsAreExplicitlyIgnoredAndMalformedTradesFailClosed() {
        assertEquals(
                List.of(new AlpacaSipInboundMessage.UnsupportedFrame("b")),
                parser.parse("[{\"T\":\"b\",\"S\":\"AAPL\"}]", RECEIVED_AT));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":1,\"x\":\"V\",\"p\":1,"
                        + "\"s\":1,\"c\":[],\"t\":\"bad\",\"z\":\"C\"}]", RECEIVED_AT));
    }
}
