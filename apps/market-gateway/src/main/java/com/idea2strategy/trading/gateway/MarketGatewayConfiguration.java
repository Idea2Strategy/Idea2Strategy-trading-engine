package com.idea2strategy.trading.gateway;

import com.idea2strategy.trading.common.runtime.FileReadinessMarker;
import com.idea2strategy.trading.common.runtime.MaterializationReceipt;
import com.idea2strategy.trading.market.alpaca.AlpacaCredentialsProvider;
import com.idea2strategy.trading.market.alpaca.AlpacaDataFeed;
import com.idea2strategy.trading.market.alpaca.AlpacaMarketEventNormalizer;
import com.idea2strategy.trading.market.alpaca.AlpacaSipMessageParser;
import com.idea2strategy.trading.market.alpaca.ApprovedSymbolUniverse;
import com.idea2strategy.trading.market.alpaca.EnvironmentAlpacaCredentialsProvider;
import com.idea2strategy.trading.market.alpaca.MarketEventOrderingProcessor;
import com.idea2strategy.trading.market.alpaca.ProviderRightsGate;
import com.idea2strategy.trading.market.alpaca.ReconnectBackoff;
import com.idea2strategy.trading.market.redis.RedisMarketEventPublisher;
import com.idea2strategy.trading.market.candle.AlpacaThirtyMinuteBarsJsonParser;
import com.idea2strategy.trading.market.candle.FinalizedCandleCycle;
import com.idea2strategy.trading.market.candle.HttpAlpacaThirtyMinuteBarsClient;
import com.idea2strategy.trading.market.display.LatestTradeCoalescer;
import com.idea2strategy.trading.market.display.RedisDisplayPricePublisher;
import com.idea2strategy.trading.market.display.RedisDisplayTradeSubscriptionSource;
import com.idea2strategy.trading.market.session.HttpAlpacaOfficialMarketSessionSource;
import com.idea2strategy.trading.market.session.OfficialMarketSessionSource;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityResult;
import com.idea2strategy.trading.market.availability.MarketDataAvailabilityStatus;
import com.idea2strategy.trading.messaging.market.MarketEventType;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "market-gateway", name = "redis-uri")
@EnableScheduling
public class MarketGatewayConfiguration {
    @Bean
    VerifiedGatewayMaterialization verifiedGatewayMaterialization(
            @Value("${market-gateway.instrument-mapping-path}") String mappingPath,
            @Value("${market-gateway.rights-evidence-path}") String evidencePath,
            @Value("${market-gateway.materialization-receipt-path}") String receiptPath) {
        MaterializationReceipt.verify(
                Path.of(receiptPath),
                Map.of(
                        "instrument-mapping", Path.of(mappingPath),
                        "provider-rights", Path.of(evidencePath)));
        return new VerifiedGatewayMaterialization();
    }

    @Bean(destroyMethod = "close")
    RedisMarketEventPublisher marketEventPublisher(
            @Value("${market-gateway.redis-uri}") String redisUri,
            @Value("${market-gateway.redis-key-prefix}") String keyPrefix,
            @Value("${market-gateway.recent-bar-capacity:390}") int recentBarCapacity,
            @Value("${market-gateway.event-stream-capacity:1000000}") int eventStreamCapacity,
            @Value("${market-gateway.event-deduplication-retention:P30D}") Duration deduplicationRetention) {
        return RedisMarketEventPublisher.connect(
                redisUri, keyPrefix, recentBarCapacity, eventStreamCapacity, deduplicationRetention);
    }

    @Bean(destroyMethod = "close")
    RedisDisplayPricePublisher displayPricePublisher(
            @Value("${market-gateway.redis-uri}") String redisUri,
            @Value("${market-gateway.redis-key-prefix}") String keyPrefix,
            @Value("${market-gateway.display-minute-bar-capacity:10000}") int minuteBarCapacity) {
        return RedisDisplayPricePublisher.connect(redisUri, keyPrefix, minuteBarCapacity);
    }

    @Bean(destroyMethod = "close")
    RedisDisplayTradeSubscriptionSource displayTradeSubscriptionSource(
            @Value("${market-gateway.redis-uri}") String redisUri,
            @Value("${market-gateway.redis-key-prefix}") String keyPrefix) {
        return RedisDisplayTradeSubscriptionSource.connect(redisUri, keyPrefix);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "market-gateway", name = "strategy-candles-enabled", havingValue = "true", matchIfMissing = true)
    OfficialMarketSessionSource officialMarketSessionSource(
            @Value("${market-gateway.alpaca-calendar-endpoint:https://api.alpaca.markets/v2/calendar}")
                    String endpoint,
            AlpacaCredentialsProvider credentialsProvider,
            Clock marketGatewayClock) {
        return new HttpAlpacaOfficialMarketSessionSource(
                HttpClient.newHttpClient(), URI.create(endpoint), credentialsProvider, marketGatewayClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "market-gateway", name = "strategy-candles-enabled", havingValue = "true", matchIfMissing = true)
    FinalizedCandleCycle finalizedCandleCycle(
            @Value("${market-gateway.alpaca-bars-endpoint:https://data.alpaca.markets/v2/stocks/bars}")
                    String endpoint,
            @Value("${market-gateway.candle-fetch-batch-size:200}") int batchSize,
            AlpacaCredentialsProvider credentialsProvider,
            RedisMarketEventPublisher publisher,
            Clock marketGatewayClock) {
        var client = new HttpAlpacaThirtyMinuteBarsClient(
                HttpClient.newHttpClient(),
                URI.create(endpoint),
                credentialsProvider,
                new AlpacaThirtyMinuteBarsJsonParser());
        var ordering = new MarketEventOrderingProcessor();
        return new FinalizedCandleCycle(client, event -> {
            publisher.publish(ordering.process(event));
            if (event.eventType() == MarketEventType.MARKET_EVALUATION_READY) {
                publisher.publishAvailability(
                        event.instrumentId(),
                        event.sequence(),
                        event.receivedAt(),
                        new MarketDataAvailabilityResult(
                                MarketDataAvailabilityStatus.AVAILABLE,
                                true,
                                true,
                                java.util.Set.of(),
                                java.util.List.of()));
            }
        }, marketGatewayClock, batchSize);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "market-gateway", name = "strategy-candles-enabled", havingValue = "true", matchIfMissing = true)
    FinalizedCandlePollingWorker finalizedCandlePollingWorker(
            FinalizedCandleCycle cycle,
            ApprovedInstruments instruments,
            OfficialMarketSessionSource sessions,
            Clock marketGatewayClock,
            @Value("${market-gateway.candle-finalization-grace:PT2S}") Duration grace) {
        return new FinalizedCandlePollingWorker(cycle, instruments, sessions, marketGatewayClock, grace);
    }

    @Bean
    ApprovedInstruments approvedInstruments(
            @Value("${market-gateway.instrument-mapping-path}") String mappingPath,
            @Value("${market-gateway.minimum-instrument-count:1}") int minimumInstrumentCount,
            VerifiedGatewayMaterialization verified) {
        return new ApprovedInstruments(
                FileInstrumentMapping.load(Path.of(mappingPath)), minimumInstrumentCount);
    }

    @Bean
    ApprovedSymbolUniverse approvedSymbolUniverse(ApprovedInstruments approvedInstruments) {
        return new ApprovedSymbolUniverse(approvedInstruments.bySymbol().keySet());
    }

    @Bean
    AlpacaMarketEventNormalizer marketEventNormalizer(ApprovedInstruments approvedInstruments) {
        return new AlpacaMarketEventNormalizer(approvedInstruments.bySymbol());
    }

    @Bean
    Clock marketGatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    FileReadinessMarker marketGatewayReadinessMarker(
            @Value("${i2s.readiness-file:/tmp/idea2strategy-ready}") String readinessFile) {
        return new FileReadinessMarker(Path.of(readinessFile));
    }

    @Bean
    ProviderRightsGate providerRightsGate(
            @Value("${market-gateway.rights-evidence-path}") String evidencePath,
            Clock marketGatewayClock,
            VerifiedGatewayMaterialization verified) {
        return new ProviderRightsGate(
                new FileProviderRightsEvidenceSource(Path.of(evidencePath)), marketGatewayClock);
    }

    @Bean
    AlpacaCredentialsProvider alpacaCredentialsProvider(Environment environment) {
        return new EnvironmentAlpacaCredentialsProvider(environment::getProperty);
    }

    @Bean
    MarketGatewayRunner marketGatewayRunner(
            @Value("${market-gateway.alpaca-feed:sip}") String feedValue,
            @Value("${market-gateway.alpaca-endpoint:}") String endpointValue,
            @Value("${market-gateway.reconnect-initial-delay:PT1S}") Duration reconnectInitialDelay,
            @Value("${market-gateway.reconnect-max-delay:PT1M}") Duration reconnectMaxDelay,
            ApprovedSymbolUniverse universe,
            ProviderRightsGate rightsGate,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaMarketEventNormalizer normalizer,
            RedisMarketEventPublisher publisher,
            ApprovedInstruments approvedInstruments,
            RedisDisplayPricePublisher displayPricePublisher,
            RedisDisplayTradeSubscriptionSource displayTradeSubscriptionSource,
            FileReadinessMarker readinessMarker,
            Clock marketGatewayClock) {
        AlpacaDataFeed feed = AlpacaDataFeed.parse(feedValue);
        URI endpoint = endpointValue.isBlank() ? feed.officialEndpoint() : URI.create(endpointValue);
        feed.validateEndpoint(endpoint);
        return new MarketGatewayRunner(
                endpoint,
                feed,
                universe,
                rightsGate,
                credentialsProvider,
                new AlpacaSipMessageParser(feed),
                normalizer,
                new MarketEventOrderingProcessor(),
                publisher,
                new LatestTradeCoalescer(approvedInstruments.bySymbol(), marketGatewayClock),
                displayPricePublisher,
                displayTradeSubscriptionSource,
                readinessMarker,
                new ReconnectBackoff(reconnectInitialDelay, reconnectMaxDelay),
                marketGatewayClock);
    }

    static final class VerifiedGatewayMaterialization {}
}
