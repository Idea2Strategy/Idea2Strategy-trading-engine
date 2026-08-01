package com.idea2strategy.trading.domain.intent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderIntentBatchFactoryTest {

    private final OrderIntentBatchFactory factory = new OrderIntentBatchFactory();

    @Test
    void createsTheSameDurableAggregateForEquivalentCandidateOrders() {
        OrderIntentBatch forward = factory.create(request(List.of(candidateOne(), candidateTwo())));
        OrderIntentBatch reverse = factory.create(request(List.of(candidateTwo(), candidateOne())));

        assertAll(
                () -> assertEquals(forward, reverse),
                () -> assertEquals(List.of(candidateOne(), candidateTwo()),
                        forward.intents().stream().map(OrderIntentIdentity::candidateId).toList()),
                () -> assertTrue(forward.requestFingerprint().matches("[0-9a-f]{64}")),
                () -> assertEquals(5, forward.batchId().version()),
                () -> assertTrue(forward.intents().stream().allMatch(intent -> intent.intentId().version() == 5)));
    }

    @Test
    void createsAnEmptyDurableAggregateForAnEmptyRequest() {
        OrderIntentBatch batch = factory.create(request(List.of()));

        assertAll(
                () -> assertTrue(batch.intents().isEmpty()),
                () -> assertEquals(5, batch.batchId().version()),
                () -> assertTrue(batch.requestFingerprint().matches("[0-9a-f]{64}")));
    }

    @Test
    void requestCopiesAndSortsCandidates() {
        ArrayList<UUID> candidates = new ArrayList<>(List.of(candidateTwo(), candidateOne()));
        OrderIntentBatchRequest request = request(candidates);
        candidates.clear();

        assertEquals(List.of(candidateOne(), candidateTwo()), request.candidateIds());
    }

    @Test
    void constructorsRejectNullIdsNullElementsAndDuplicates() {
        OrderIntentIdentity identityOne = new OrderIntentIdentity(intentOne(), candidateOne());
        OrderIntentIdentity identityTwo = new OrderIntentIdentity(intentTwo(), candidateTwo());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatchRequest(
                        null, evaluationId(), sourceCandidateBatchId(), List.of(candidateOne()))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatchRequest(
                        botId(), null, sourceCandidateBatchId(), List.of(candidateOne()))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatchRequest(
                        botId(), evaluationId(), null, List.of(candidateOne()))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatchRequest(
                        botId(), evaluationId(), sourceCandidateBatchId(), Collections.singletonList(null))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(List.of(candidateOne(), candidateOne()))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentIdentity(null, candidateOne())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentIdentity(intentOne(), null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        null, botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(), List.of(identityOne))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), null, evaluationId(), sourceCandidateBatchId(), fingerprint(), List.of(identityOne))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(),
                        Collections.singletonList(null))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(),
                        List.of(identityTwo, identityOne))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(),
                        List.of(identityOne, new OrderIntentIdentity(intentTwo(), candidateOne())))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(),
                        List.of(identityOne, new OrderIntentIdentity(intentOne(), candidateTwo())))),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), "not-a-fingerprint", List.of(identityOne))),
                () -> assertThrows(IllegalArgumentException.class, () -> factory.create(null)),
                () -> assertDoesNotThrow(() -> new OrderIntentBatch(
                        UUID.randomUUID(), botId(), evaluationId(), sourceCandidateBatchId(), fingerprint(),
                        List.of(identityOne, identityTwo))));
    }

    private static OrderIntentBatchRequest request(List<UUID> candidates) {
        return new OrderIntentBatchRequest(botId(), evaluationId(), sourceCandidateBatchId(), candidates);
    }

    private static UUID botId() {
        return UUID.fromString("10000000-0000-0000-0000-000000000001");
    }

    private static UUID evaluationId() {
        return UUID.fromString("20000000-0000-0000-0000-000000000002");
    }

    private static UUID sourceCandidateBatchId() {
        return UUID.fromString("30000000-0000-0000-0000-000000000003");
    }

    private static UUID candidateOne() {
        return UUID.fromString("40000000-0000-0000-0000-000000000004");
    }

    private static UUID candidateTwo() {
        return UUID.fromString("50000000-0000-0000-0000-000000000005");
    }

    private static UUID intentOne() {
        return UUID.fromString("60000000-0000-0000-0000-000000000006");
    }

    private static UUID intentTwo() {
        return UUID.fromString("70000000-0000-0000-0000-000000000007");
    }

    private static String fingerprint() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }
}
