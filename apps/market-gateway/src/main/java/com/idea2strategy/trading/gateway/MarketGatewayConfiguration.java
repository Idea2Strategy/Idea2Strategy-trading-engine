package com.idea2strategy.trading.gateway;

import com.idea2strategy.trading.market.alpaca.AlpacaCredentialsProvider;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "market-gateway", name = "redis-uri")
public class MarketGatewayConfiguration {
    @Bean(destroyMethod = "close")
    RedisMarketEventPublisher marketEventPublisher(
            @Value("${market-gateway.redis-uri}") String redisUri,
            @Value("${market-gateway.redis-key-prefix}") String keyPrefix) {
        return RedisMarketEventPublisher.connect(redisUri, keyPrefix);
    }

    @Bean
    ApprovedInstruments approvedInstruments(
            @Value("${market-gateway.instrument-mapping-path}") String mappingPath) {
        return new ApprovedInstruments(FileInstrumentMapping.load(Path.of(mappingPath)));
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
    ProviderRightsGate providerRightsGate(
            @Value("${market-gateway.rights-evidence-path}") String evidencePath,
            Clock marketGatewayClock) {
        return new ProviderRightsGate(
                new FileProviderRightsEvidenceSource(Path.of(evidencePath)), marketGatewayClock);
    }

    @Bean
    AlpacaCredentialsProvider alpacaCredentialsProvider(Environment environment) {
        return new EnvironmentAlpacaCredentialsProvider(environment::getProperty);
    }

    @Bean
    MarketGatewayRunner marketGatewayRunner(
            @Value("${market-gateway.alpaca-endpoint:wss://stream.data.alpaca.markets/v2/sip}") URI endpoint,
            @Value("${market-gateway.reconnect-initial-delay:PT1S}") Duration reconnectInitialDelay,
            @Value("${market-gateway.reconnect-max-delay:PT1M}") Duration reconnectMaxDelay,
            ApprovedSymbolUniverse universe,
            ProviderRightsGate rightsGate,
            AlpacaCredentialsProvider credentialsProvider,
            AlpacaMarketEventNormalizer normalizer,
            RedisMarketEventPublisher publisher,
            Clock marketGatewayClock) {
        return new MarketGatewayRunner(
                endpoint,
                universe,
                rightsGate,
                credentialsProvider,
                new AlpacaSipMessageParser(),
                normalizer,
                new MarketEventOrderingProcessor(),
                publisher,
                new ReconnectBackoff(reconnectInitialDelay, reconnectMaxDelay),
                marketGatewayClock);
    }
}
