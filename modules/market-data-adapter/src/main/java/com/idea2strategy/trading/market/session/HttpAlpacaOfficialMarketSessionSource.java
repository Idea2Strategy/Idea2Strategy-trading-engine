package com.idea2strategy.trading.market.session;

import com.idea2strategy.trading.market.alpaca.AlpacaCredentials;
import com.idea2strategy.trading.market.alpaca.AlpacaCredentialsProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** Daily cached official Alpaca calendar lookup, including holidays and early closes. */
public final class HttpAlpacaOfficialMarketSessionSource implements OfficialMarketSessionSource {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.alpaca.markets/v2/calendar");

    private final HttpClient client;
    private final URI endpoint;
    private final AlpacaCredentialsProvider credentialsProvider;
    private final AlpacaCalendarJsonParser parser;
    private final Clock clock;
    private volatile LocalDate cachedDate;
    private volatile Optional<OfficialMarketSession> cachedSession = Optional.empty();

    public HttpAlpacaOfficialMarketSessionSource(
            HttpClient client,
            URI endpoint,
            AlpacaCredentialsProvider credentialsProvider,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.parser = new AlpacaCalendarJsonParser();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Optional<OfficialMarketSession> session(LocalDate tradingDate) {
        Objects.requireNonNull(tradingDate, "tradingDate");
        if (tradingDate.equals(cachedDate)) {
            return cachedSession;
        }
        AlpacaCredentials credentials = credentialsProvider.load();
        URI uri = URI.create(endpoint + "?start=" + tradingDate + "&end=" + tradingDate);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("APCA-API-KEY-ID", credentials.apiKey())
                .header("APCA-API-SECRET-KEY", credentials.apiSecret())
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Alpaca calendar request failed with HTTP " + response.statusCode());
            }
            cachedSession = parser.parse(response.body(), tradingDate, tradingDate, clock.instant())
                    .sessions().stream().findFirst();
            cachedDate = tradingDate;
            return cachedSession;
        } catch (IOException exception) {
            throw new IllegalStateException("Alpaca calendar request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Alpaca calendar request was interrupted", exception);
        }
    }
}
