package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class AlpacaDataFeedTest {
    @Test
    void onlyExplicitSipAndIexValuesAreAccepted() {
        assertEquals(AlpacaDataFeed.SIP, AlpacaDataFeed.parse("sip"));
        assertEquals(AlpacaDataFeed.IEX, AlpacaDataFeed.parse("IEX"));
        assertThrows(IllegalArgumentException.class, () -> AlpacaDataFeed.parse("delayed_sip"));
        assertThrows(IllegalArgumentException.class, () -> AlpacaDataFeed.parse(""));
    }

    @Test
    void officialEndpointMustMatchTheSelectedFeed() {
        AlpacaDataFeed.IEX.validateEndpoint(URI.create("wss://stream.data.alpaca.markets/v2/iex"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AlpacaDataFeed.IEX.validateEndpoint(
                        URI.create("wss://stream.data.alpaca.markets/v2/sip")));
    }
}
