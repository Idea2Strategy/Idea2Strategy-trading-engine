package com.idea2strategy.trading.worker.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idea2strategy.trading.domain.candidate.CandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidate;
import com.idea2strategy.trading.messaging.evaluation.OrderCandidateBatch;
import com.idea2strategy.trading.messaging.evaluation.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderCandidateBatchAdapterTest {

    @Test
    void translatesComCMessageWithoutLosingOrderIntent() {
        OrderCandidateBatch source = new OrderCandidateBatch(
                1,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new OrderCandidate(
                        UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        OrderSide.SELL,
                        new BigDecimal("3.5"),
                        new BigDecimal("42.75"),
                        List.of("rebalance", "risk-approved"))));

        CandidateBatch translated = new OrderCandidateBatchAdapter().toDomain(source);

        assertEquals(source.batchId(), translated.batchId());
        assertEquals(source.evaluationId(), translated.evaluationId());
        assertEquals(source.createdAt(), translated.createdAt());
        assertEquals(1, translated.candidates().size());
        assertEquals(source.candidates().getFirst().candidateId(), translated.candidates().getFirst().candidateId());
        assertEquals("SELL", translated.candidates().getFirst().side());
        assertEquals(new BigDecimal("3.5"), translated.candidates().getFirst().quantity());
        assertEquals(new BigDecimal("42.75"), translated.candidates().getFirst().limitPrice());
        assertEquals(List.of("rebalance", "risk-approved"), translated.candidates().getFirst().reasonCodes());
    }
}
