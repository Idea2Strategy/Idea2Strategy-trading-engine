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
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "market-gateway", name = "redis-uri")
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
            @Value("${market-gateway.recent-bar-capacity:390}") int recentBarCapacity) {
        return RedisMarketEventPublisher.connect(redisUri, keyPrefix, recentBarCapacity);
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
                readinessMarker,
                new ReconnectBackoff(reconnectInitialDelay, reconnectMaxDelay),
                marketGatewayClock);
    }

    static final class VerifiedGatewayMaterialization {}
}
