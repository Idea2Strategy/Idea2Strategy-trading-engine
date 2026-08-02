package com.idea2strategy.trading.worker.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.trading.market.warmup.ManifestBoundWarmupDataSource;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WarmupConfigurationTest {
    @TempDir
    Path bundleRoot;

    @Test
    void wiresTheManifestConsumerAndStartupGateOnlyWhenABundleRootIsConfigured() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(WarmupConfiguration.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(BotStartupWarmupGate.class));
        runner.withPropertyValues("trading.warmup.bundle-root=" + bundleRoot)
                .run(context -> {
                    assertThat(context).hasSingleBean(ManifestBoundWarmupDataSource.class);
                    assertThat(context).hasSingleBean(StartupWarmupCoordinator.class);
                    assertThat(context).hasSingleBean(BotStartupWarmupGate.class);
                });
    }
}
