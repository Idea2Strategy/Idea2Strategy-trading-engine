package com.idea2strategy.trading.worker.candidate;

import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FakeCandidateBatchSource {
    public static final UUID BATCH_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");

    public OrderCandidateBatch nextBatch() {
        return new OrderCandidateBatch(
                1,
                BATCH_ID,
                UUID.fromString("82000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new OrderCandidate(
                        UUID.fromString("83000000-0000-0000-0000-000000000003"),
                        UUID.fromString("84000000-0000-0000-0000-000000000004"),
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        new BigDecimal("100.00"),
                        List.of("fake-candidate-source"))));
    }
}
