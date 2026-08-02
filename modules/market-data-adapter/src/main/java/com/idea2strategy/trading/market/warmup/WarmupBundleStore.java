package com.idea2strategy.trading.market.warmup;

import java.io.IOException;
import java.util.Optional;

@FunctionalInterface
public interface WarmupBundleStore {
    Optional<byte[]> read(String objectKey) throws IOException;
}
