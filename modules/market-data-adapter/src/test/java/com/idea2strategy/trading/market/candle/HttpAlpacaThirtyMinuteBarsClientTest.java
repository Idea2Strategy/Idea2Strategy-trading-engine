package com.idea2strategy.trading.market.candle;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.trading.market.alpaca.AlpacaCredentials;
import com.idea2strategy.trading.market.session.OfficialMarketSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpAlpacaThirtyMinuteBarsClientTest {
    @Test
    @SuppressWarnings("unchecked")
    void requestsAdjustmentAllSoLiveBarsCanMergeWithCanonicalAdjustedHistory() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AlpacaThirtyMinuteBarsJsonParser parser = mock(AlpacaThirtyMinuteBarsJsonParser.class);
        when(parser.parse(any(), any(), any(), any())).thenReturn(Map.of());
        HttpAlpacaThirtyMinuteBarsClient client = new HttpAlpacaThirtyMinuteBarsClient(
                http,
                URI.create("https://example.test/v2/stocks/bars"),
                () -> new AlpacaCredentials("key", "secret"),
                parser);
        OfficialMarketSession session = new OfficialMarketSession(
                LocalDate.parse("2026-08-10"),
                Instant.parse("2026-08-10T13:30:00Z"),
                Instant.parse("2026-08-10T20:00:00Z"));

        client.fetch(
                Map.of("AAPL", UUID.fromString("70000000-0000-4000-8000-000000000001")),
                session,
                session.closesAt());

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(request.getValue().uri().getRawQuery().contains("adjustment=all"));
    }
}
