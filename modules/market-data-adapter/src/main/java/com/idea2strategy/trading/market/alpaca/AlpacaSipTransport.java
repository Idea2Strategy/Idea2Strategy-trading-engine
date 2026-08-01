package com.idea2strategy.trading.market.alpaca;

import java.util.List;

public interface AlpacaSipTransport {
    void authenticate(AlpacaCredentials credentials);

    void subscribe(List<String> symbols);
}
