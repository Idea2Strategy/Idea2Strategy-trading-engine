package com.idea2strategy.trading.market.candle;

import com.idea2strategy.trading.market.session.OfficialMarketSession;
import com.idea2strategy.trading.messaging.market.MarketCandle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@FunctionalInterface
public interface AlpacaThirtyMinuteBarsClient {
    Map<String, List<MarketCandle>> fetch(
            Map<String, UUID> instruments,
            OfficialMarketSession session,
            Instant throughBoundary);
}
