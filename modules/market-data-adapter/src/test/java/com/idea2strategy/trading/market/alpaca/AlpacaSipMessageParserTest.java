package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlpacaSipMessageParserTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-31T14:31:00.050Z");

    private final AlpacaSipMessageParser parser = new AlpacaSipMessageParser();

    @Test
    void parsesTheOfficialControlFrameSequence() {
        assertEquals(
                List.of(new AlpacaSipInboundMessage.Connected()),
                parser.parse("[{\"T\":\"success\",\"msg\":\"connected\"}]", RECEIVED_AT));
        assertEquals(
                List.of(new AlpacaSipInboundMessage.Authenticated()),
                parser.parse("[{\"T\":\"success\",\"msg\":\"authenticated\"}]", RECEIVED_AT));
        assertEquals(
                List.of(new AlpacaSipInboundMessage.SubscriptionConfirmed(List.of("AAPL", "MSFT"))),
                parser.parse(
                        "[{\"T\":\"subscription\",\"trades\":[\"AAPL\",\"MSFT\"],"
                                + "\"quotes\":[\"AAPL\",\"MSFT\"],\"bars\":[\"AAPL\",\"MSFT\"]}]",
                        RECEIVED_AT));
        assertEquals(
                List.of(new AlpacaSipInboundMessage.ProviderError(406, "connection limit exceeded")),
                parser.parse("[{\"T\":\"error\",\"code\":406,\"msg\":\"connection limit exceeded\"}]", RECEIVED_AT));
    }

    @Test
    void parsesMinuteBarIntoTheProviderNeutralContractShape() {
        List<AlpacaSipInboundMessage> messages = parser.parse(
                "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":210.10,\"h\":210.25,\"l\":210.05,"
                        + "\"c\":210.20,\"v\":2500,\"t\":\"2026-07-31T14:30:00Z\",\"n\":181,\"vw\":210.17}]",
                RECEIVED_AT);

        Instant barStart = Instant.parse("2026-07-31T14:30:00Z");
        AlpacaMarketInput expected = new AlpacaMarketInput(
                MarketEventType.BAR_1M,
                "bar-20260731T143000Z",
                "AAPL",
                "SIP",
                barStart,
                RECEIVED_AT,
                barStart.getEpochSecond() / 60,
                0,
                Map.of(
                        "open", new BigDecimal("210.10"),
                        "high", new BigDecimal("210.25"),
                        "low", new BigDecimal("210.05"),
                        "close", new BigDecimal("210.20"),
                        "volume", new BigDecimal("2500")));
        assertEquals(List.of(new AlpacaSipInboundMessage.MinuteBar(expected)), messages);
    }

    @Test
    void barSequenceIsTheEpochMinuteSoRedeliveryAndRestartsAgree() {
        String frame = "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":1,\"h\":1,\"l\":1,\"c\":1,\"v\":1,"
                + "\"t\":\"2026-07-31T14:30:00Z\"}]";

        AlpacaSipInboundMessage.MinuteBar first = (AlpacaSipInboundMessage.MinuteBar)
                parser.parse(frame, RECEIVED_AT).get(0);
        AlpacaSipInboundMessage.MinuteBar redelivered = (AlpacaSipInboundMessage.MinuteBar)
                parser.parse(frame, RECEIVED_AT.plusSeconds(90)).get(0);

        assertEquals(first.input().sequence(), redelivered.input().sequence());
        assertEquals(first.input().providerEventId(), redelivered.input().providerEventId());
        assertEquals(
                first.input().sequence() + 1,
                ((AlpacaSipInboundMessage.MinuteBar) parser.parse(
                        frame.replace("14:30:00Z", "14:31:00Z"), RECEIVED_AT.plusSeconds(60)).get(0))
                        .input().sequence());
    }

    @Test
    void quoteTradeAndUpdatedBarFramesAreReportedNotSilentlyDropped() {
        List<AlpacaSipInboundMessage> messages = parser.parse(
                "[{\"T\":\"q\",\"S\":\"AAPL\"},{\"T\":\"t\",\"S\":\"AAPL\"},{\"T\":\"u\",\"S\":\"AAPL\"}]",
                RECEIVED_AT);

        assertEquals(
                List.of(
                        new AlpacaSipInboundMessage.UnsupportedFrame("q"),
                        new AlpacaSipInboundMessage.UnsupportedFrame("t"),
                        new AlpacaSipInboundMessage.UnsupportedFrame("u")),
                messages);
    }

    @Test
    void rejectsMalformedFramesInsteadOfGuessing() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not json", RECEIVED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("{\"T\":\"success\",\"msg\":\"connected\"}", RECEIVED_AT));
        IllegalArgumentException missingField = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":1,\"h\":1,\"l\":1,\"c\":1,"
                                + "\"t\":\"2026-07-31T14:30:00Z\"}]",
                        RECEIVED_AT));
        assertInstanceOf(IllegalArgumentException.class, missingField);
        assertEquals("SIP bar field v must be a number", missingField.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":1,\"h\":1,\"l\":1,\"c\":1,\"v\":1,"
                                + "\"t\":\"yesterday\"}]",
                        RECEIVED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"]}]", RECEIVED_AT));
    }
}
