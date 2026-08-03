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
        // A version beyond the allocation one is refused even though it carries a complete scope,
        // because its meaning has not been agreed with the producer yet.
        OrderCandidateBatch source = new OrderCandidateBatch(
                OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION + 1,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                UUID.fromString("60000000-0000-0000-0000-000000000006"),
                UUID.fromString("70000000-0000-0000-0000-000000000007"),
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> new OrderCandidateBatchAdapter().toDomain(source));
    }

    /** A version 3 buy hands over its share and lets this service size it. */
    @Test
    void carriesTheAllocationShareOfASchemaVersionThreeBuy() {
        CandidateBatch translated = new OrderCandidateBatchAdapter().toDomain(allocationBatch(
                OrderCandidate.allocatedBuy(
                        CANDIDATE, INSTRUMENT, FLOW, 1, 3, null, List.of("BASIC_RULE_MATCHED"))));

        var candidate = translated.candidates().getFirst();
        assertEquals("BUY", candidate.side());
        assertTrue(candidate.requestedQuantity().isEmpty(), "a version 3 candidate states no quantity");
        assertEquals(1, candidate.allocationShare().orElseThrow().numerator());
        assertEquals(3, candidate.allocationShare().orElseThrow().denominator());
    }

    /** A version 3 sell states nothing: its size is the position this service's lots hold. */
    @Test
    void carriesNoMeasureForASchemaVersionThreeSell() {
        CandidateBatch translated = new OrderCandidateBatchAdapter().toDomain(allocationBatch(
                OrderCandidate.heldSell(CANDIDATE, INSTRUMENT, FLOW, null, List.of("EXIT"))));

        var candidate = translated.candidates().getFirst();
        assertEquals("SELL", candidate.side());
        assertTrue(candidate.requestedQuantity().isEmpty());
        assertTrue(candidate.allocationShare().isEmpty());
    }

    /**
     * The per-version shape is enforced, because both silent defaults are dangerous: a missing
     * version 2 quantity read as zero drops a trade the producer asked for, and a missing version 3
     * share read as "all of it" spends the whole partition on one instrument.
     */
    @Test
    void refusesACandidateThatDoesNotMatchItsSchemaVersion() {
        OrderCandidateBatch quantityAtVersionThree = allocationBatch(new OrderCandidate(
                CANDIDATE, INSTRUMENT, FLOW, OrderSide.BUY, new BigDecimal("2"), null,
                List.of("BASIC_RULE_MATCHED")));
        OrderCandidateBatch buyWithoutShareAtVersionThree = allocationBatch(new OrderCandidate(
                CANDIDATE, INSTRUMENT, FLOW, OrderSide.BUY, null, null, null, null,
                List.of("BASIC_RULE_MATCHED")));
        OrderCandidateBatch shareAtVersionTwo = new OrderCandidateBatch(
                OrderCandidateBatch.SCOPED_SCHEMA_VERSION,
                BATCH, EVALUATION, BOT, PARTITION, SOURCE_EVENT,
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(OrderCandidate.allocatedBuy(
                        CANDIDATE, INSTRUMENT, FLOW, 1, 2, null, List.of("BASIC_RULE_MATCHED"))));

        var adapter = new OrderCandidateBatchAdapter();
        assertThrows(IllegalArgumentException.class, () -> adapter.toDomain(quantityAtVersionThree));
        assertThrows(IllegalArgumentException.class, () -> adapter.toDomain(buyWithoutShareAtVersionThree));
        assertThrows(IllegalArgumentException.class, () -> adapter.toDomain(shareAtVersionTwo));
    }

    /** Invariants the record holds at every version. */
    @Test
    void refusesAShareThatIsNotAFractionOfSomethingThisServiceCanSpend() {
        assertThrows(IllegalArgumentException.class, () -> OrderCandidate.allocatedBuy(
                CANDIDATE, INSTRUMENT, FLOW, 0, 3, null, List.of("X")));
        assertThrows(IllegalArgumentException.class, () -> OrderCandidate.allocatedBuy(
                CANDIDATE, INSTRUMENT, FLOW, 4, 3, null, List.of("X")));
        assertThrows(IllegalArgumentException.class, () -> OrderCandidate.allocatedBuy(
                CANDIDATE, INSTRUMENT, FLOW, 1, 0, null, List.of("X")));
        // A sell carrying a buy share would imply a fraction of something the producer cannot see.
        assertThrows(IllegalArgumentException.class, () -> new OrderCandidate(
                CANDIDATE, INSTRUMENT, FLOW, OrderSide.SELL, null, 1, 2, null, List.of("X")));
        // Two measures for one candidate leave no single answer to "how much".
        assertThrows(IllegalArgumentException.class, () -> new OrderCandidate(
                CANDIDATE, INSTRUMENT, FLOW, OrderSide.BUY, new BigDecimal("2"), 1, 2, null,
                List.of("X")));
    }

    private static final UUID BATCH = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EVALUATION = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CANDIDATE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID INSTRUMENT = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID BOT = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID PARTITION = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID SOURCE_EVENT = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final UUID FLOW = UUID.fromString("80000000-0000-0000-0000-000000000008");

    private static OrderCandidateBatch allocationBatch(OrderCandidate candidate) {
        return new OrderCandidateBatch(
                OrderCandidateBatch.ALLOCATION_SCHEMA_VERSION,
                BATCH, EVALUATION, BOT, PARTITION, SOURCE_EVENT,
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(candidate));
    }
}
