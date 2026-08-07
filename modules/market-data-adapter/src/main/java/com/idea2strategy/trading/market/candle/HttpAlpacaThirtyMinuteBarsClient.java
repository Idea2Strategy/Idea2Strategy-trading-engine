package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.market.alpaca.AlpacaCredentials;
import com.idea2strategy.trading.market.alpaca.AlpacaCredentialsProvider;
import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Batch REST client for finalized Alpaca 30Min bars; it never requests or reconstructs 1m bars. */
public final class HttpAlpacaThirtyMinuteBarsClient implements AlpacaThirtyMinuteBarsClient {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://data.alpaca.markets/v2/stocks/bars");

    private final HttpClient httpClient;
    private final URI endpoint;
    private final AlpacaCredentialsProvider credentialsProvider;
    private final AlpacaThirtyMinuteBarsJsonParser parser;

    public HttpAlpacaThirtyMinuteBarsClient(
            HttpClient httpClient,
            URI endpoint,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaThirtyMinuteBarsJsonParser parser) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Map<String, List<MarketCandle>> fetch(
            Map<String, UUID> instruments,
            OfficialMarketSession session,
            Instant throughBoundary) {
        if (instruments.isEmpty()) {
            return Map.of();
        }
        AlpacaCredentials credentials = credentialsProvider.load();
        String symbols = String.join(",", instruments.keySet());
        String query = "symbols=" + encode(symbols)
                + "&timeframe=30Min&start=" + encode(session.opensAt().toString())
                + "&end=" + encode(throughBoundary.toString())
                + "&limit=10000&feed=sip&adjustment=raw&sort=asc";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .header("APCA-API-KEY-ID", credentials.apiKey())
                .header("APCA-API-SECRET-KEY", credentials.apiSecret())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Alpaca 30Min bars request failed with HTTP " + response.statusCode());
            }
            return parser.parse(response.body(), instruments, session, throughBoundary);
        } catch (IOException exception) {
            throw new IllegalStateException("Alpaca 30Min bars request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Alpaca 30Min bars request was interrupted", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
