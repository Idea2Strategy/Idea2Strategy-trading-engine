package com.idea2strategy.trading.market.alpaca;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class AlpacaSipWebSocketTransport implements AlpacaSipTransport {
    public static final URI SIP_ENDPOINT = URI.create("wss://stream.data.alpaca.markets/v2/sip");

    private final TextFrameSender sender;

    public AlpacaSipWebSocketTransport(TextFrameSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public static AlpacaSipWebSocketTransport connected(WebSocket webSocket) {
        Objects.requireNonNull(webSocket, "webSocket");
        return new AlpacaSipWebSocketTransport(text -> webSocket.sendText(text, true));
    }

    @Override
    public void authenticate(AlpacaCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        sender.send("{\"action\":\"auth\",\"key\":" + quote(credentials.apiKey())
                + ",\"secret\":" + quote(credentials.apiSecret()) + "}");
    }

    @Override
    public void subscribe(List<String> symbols) {
        ApprovedSymbolUniverse universe = new ApprovedSymbolUniverse(symbols);
        String symbolArray = universe.symbols().stream()
                .map(AlpacaSipWebSocketTransport::quote)
                .collect(Collectors.joining(",", "[", "]"));
        sender.send("{\"action\":\"subscribe\",\"trades\":" + symbolArray
                + ",\"quotes\":" + symbolArray
                + ",\"bars\":" + symbolArray + "}");
    }

    private static String quote(String value) {
        String escaped = Objects.requireNonNull(value, "value")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    @FunctionalInterface
    public interface TextFrameSender {
        CompletionStage<?> send(String text);
    }
}
