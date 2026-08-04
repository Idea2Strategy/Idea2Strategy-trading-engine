package com.idea2strategy.trading.worker.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import com.idea2strategy.trading.market.warmup.ManifestBoundWarmupDataSource;
import com.idea2strategy.trading.strategy.runtime.warmup.StartupWarmupCoordinator;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WarmupConfigurationTest {
    @TempDir
    Path bundleRoot;

    @Test
    void wiresTheManifestConsumerOnlyAfterTheVersionedBundleReceiptVerifies() throws Exception {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(WarmupConfiguration.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(BotStartupWarmupGate.class));
        runner.withPropertyValues("trading.warmup.bundle-root=" + bundleRoot)
                .run(context -> assertThat(context).hasFailed());

        Path manifest = Files.writeString(bundleRoot.resolve("manifest.json"), "{}");
        Path receipt = Files.writeString(bundleRoot.resolve("receipt.properties"), """
                contract=i2s.materialization-receipt
                schema-version=1
                artifact-count=1
                artifact.0.id=warmup-manifest
                artifact.0.source-bucket=runtime-bucket
                artifact.0.source-key=trading/warmup/manifest.json
                artifact.0.source-version-id=version-1
                artifact.0.sha256=%s
                artifact.0.local-path=%s
                """.formatted(
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(manifest))),
                        manifest.toAbsolutePath().normalize().toString().replace('\\', '/')));

        runner.withPropertyValues(
                        "trading.warmup.bundle-root=" + bundleRoot,
                        "trading.warmup.materialization-receipt-path=" + receipt)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ManifestBoundWarmupDataSource.class);
                    assertThat(context).hasSingleBean(StartupWarmupCoordinator.class);
                    assertThat(context).hasSingleBean(BotStartupWarmupGate.class);
                });
    }
}
