package com.idea2strategy.trading.market.alpaca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AlpacaSipWebSocketTransportTest {
    @Test
    void emitsOfficialAlpacaAuthenticationAndStockSubscriptionFrames() {
        List<String> frames = new ArrayList<>();
        AlpacaSipWebSocketTransport transport = new AlpacaSipWebSocketTransport(text -> {
            frames.add(text);
            return CompletableFuture.completedFuture(null);
        });

        transport.authenticate(new AlpacaCredentials("api-key", "api-secret"));
        transport.subscribe(List.of("msft", "AAPL", "AAPL"));

        assertEquals(
                List.of(
                        "{\"action\":\"auth\",\"key\":\"api-key\",\"secret\":\"api-secret\"}",
                        "{\"action\":\"subscribe\",\"trades\":[\"AAPL\",\"MSFT\"],"
                                + "\"quotes\":[\"AAPL\",\"MSFT\"],\"bars\":[\"AAPL\",\"MSFT\"]}"),
                frames);
    }
}
