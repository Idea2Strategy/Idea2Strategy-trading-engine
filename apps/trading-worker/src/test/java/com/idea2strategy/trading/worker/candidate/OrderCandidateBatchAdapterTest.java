package com.idea2strategy.trading.worker.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void carriesThePartitionScopeOfASchemaVersionTwoBatch() {
        UUID bot = UUID.fromString("50000000-0000-0000-0000-000000000005");
        UUID partition = UUID.fromString("60000000-0000-0000-0000-000000000006");
        UUID sourceEvent = UUID.fromString("70000000-0000-0000-0000-000000000007");
        UUID flow = UUID.fromString("80000000-0000-0000-0000-000000000008");
        OrderCandidateBatch source = new OrderCandidateBatch(
                2,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                bot, partition, sourceEvent,
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new OrderCandidate(
                        UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        flow,
                        OrderSide.BUY,
                        new BigDecimal("2"),
                        null,
                        List.of("BASIC_RULE_MATCHED"))));

        CandidateBatch translated = new OrderCandidateBatchAdapter().toDomain(source);

        assertTrue(translated.carriesPartitionScope());
        assertEquals(bot, translated.botId());
        assertEquals(partition, translated.partitionId());
        assertEquals(sourceEvent, translated.sourceEventId());
        assertEquals(flow, translated.candidates().getFirst().flowId());
    }

    @Test
    void aSchemaVersionOneBatchIsProcessedButIsNotCanonicalReady() {
        OrderCandidateBatch source = new OrderCandidateBatch(
                1,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of());

        assertFalse(new OrderCandidateBatchAdapter().toDomain(source).carriesPartitionScope());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        // A version beyond the scoped one is refused even though it carries a complete scope,
        // because its meaning has not been agreed with the producer yet.
        OrderCandidateBatch source = new OrderCandidateBatch(
                3,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                UUID.fromString("60000000-0000-0000-0000-000000000006"),
                UUID.fromString("70000000-0000-0000-0000-000000000007"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> new OrderCandidateBatchAdapter().toDomain(source));
    }
}
