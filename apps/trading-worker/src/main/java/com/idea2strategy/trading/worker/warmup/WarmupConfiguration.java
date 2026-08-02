package com.idea2strategy.trading.worker.warmup;

import com.idea2strategy.trading.market.warmup.FileWarmupBundleStore;
import com.idea2strategy.trading.market.warmup.ManifestBoundWarmupDataSource;
import com.idea2strategy.trading.market.warmup.WarmupBundleStore;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import com.idea2strategy.trading.strategy.runtime.warmup.WarmupDataSource;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "trading.warmup", name = "bundle-root")
public class WarmupConfiguration {
    @Bean
    WarmupBundleStore warmupBundleStore(@Value("${trading.warmup.bundle-root}") String bundleRoot) {
        return new FileWarmupBundleStore(Path.of(bundleRoot));
    }

    @Bean
    ManifestBoundWarmupDataSource warmupDataSource(
            WarmupBundleStore store,
            @Value("${trading.warmup.manifest-key:manifest.json}") String manifestKey) {
        return new ManifestBoundWarmupDataSource(store, manifestKey);
    }

    @Bean
    StartupWarmupCoordinator startupWarmupCoordinator(WarmupDataSource source) {
        return new StartupWarmupCoordinator(
                source, "1", Set.of("warmup-bars-v1", "feature-object-v1"));
    }

    @Bean
    BotStartupWarmupGate botStartupWarmupGate(StartupWarmupCoordinator coordinator) {
        return new BotStartupWarmupGate(coordinator);
    }
}
