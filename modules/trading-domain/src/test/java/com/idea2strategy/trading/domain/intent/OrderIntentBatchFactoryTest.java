package com.idea2strategy.trading.domain.intent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.trading.domain.eligibility.OrderPositionEffect;
import com.idea2strategy.trading.domain.order.OrderSide;
import com.idea2strategy.trading.domain.order.OrderType;
import com.idea2strategy.trading.domain.order.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderIntentBatchFactoryTest {

    private final OrderIntentBatchFactory factory = new OrderIntentBatchFactory();

    @Test
    void createsTheSameDurableAggregateForEquivalentCandidateOrders() {
        OrderIntentBatch forward = factory.create(request(List.of(approved(ONE), approved(TWO))));
        OrderIntentBatch reverse = factory.create(request(List.of(approved(TWO), approved(ONE))));

        assertAll(
                () -> assertEquals(forward, reverse),
                () -> assertEquals(
                        List.of(ONE, TWO),
                        forward.intents().stream().map(OrderIntent::candidateId).toList()),
                () -> assertTrue(forward.inputStateHash().matches("[0-9a-f]{64}")),
                () -> assertTrue(forward.resultHash().matches("[0-9a-f]{64}")),
                () -> assertTrue(forward.conflictPolicyHash().matches("[0-9a-f]{64}")),
                () -> assertEquals(5, forward.batchId().version()),
                () -> assertEquals(2, forward.batchId().variant()),
                () -> assertTrue(forward.intents().stream().allMatch(i -> i.intentId().version() == 5)),
                () -> assertTrue(forward.intents().stream().allMatch(i -> i.intentId().variant() == 2)));
    }

    /**
     * The identifiers are derived from the evaluation alone, so these vectors are unchanged by the
     * move to canonical storage. They are what a redelivery has to land on to be recognised as one.
     */
    @Test
    void createsTheApprovedDeterministicIdentityVector() {
        OrderIntentBatch batch = factory.create(request(List.of(approved(TWO), approved(ONE))));

        assertAll(
                () -> assertEquals(
                        UUID.fromString("c57f84cb-a69c-5dc3-86d7-45cbf9136b30"), batch.batchId()),
                () -> assertEquals(
                        List.of(
                                UUID.fromString("9a701646-1970-51c6-9393-12eb10d6f437"),
                                UUID.fromString("fb7cd373-3c3d-56d0-928e-34b66123f082")),
                        batch.intents().stream().map(OrderIntent::intentId).toList()),
                () -> assertEquals(
                        List.of("candidate:" + ONE, "candidate:" + TWO),
                        batch.intents().stream().map(OrderIntent::intentKey).toList()));
    }

    @Test
    void createsAnEmptyDurableAggregateForAnEvaluationWithNoCandidate() {
        OrderIntentBatch batch = factory.create(request(List.of()));

        assertAll(
                () -> assertTrue(batch.intents().isEmpty()),
                () -> assertEquals(5, batch.batchId().version()),
                () -> assertTrue(batch.inputStateHash().matches("[0-9a-f]{64}")),
                () -> assertTrue(batch.resultHash().matches("[0-9a-f]{64}")));
    }

    @Test
    void derivesIdentityOnlyFromTheEvaluationAndCandidate() {
        OrderIntentBatch baseline = factory.create(request(List.of(approved(ONE), approved(TWO))));
        OrderIntentBatch changedBot = factory.create(
                request(OTHER_BOT, PARTITION, EVENT, EVALUATION, SOURCE, List.of(approved(ONE), approved(TWO))));
        OrderIntentBatch changedEvaluation = factory.create(
                request(BOT, PARTITION, EVENT, OTHER_EVALUATION, SOURCE, List.of(approved(ONE), approved(TWO))));

        assertAll(
                () -> assertEquals(baseline.batchId(), changedBot.batchId()),
                () -> assertEquals(intentFor(baseline, ONE), intentFor(changedBot, ONE)),
                () -> assertNotEquals(baseline.batchId(), changedEvaluation.batchId()),
                () -> assertNotEquals(intentFor(baseline, ONE), intentFor(changedEvaluation, ONE)));
    }

    @Test
    void hashesEveryInputWhileIgnoringCandidateArrivalOrder() {
        OrderIntentBatch baseline = factory.create(request(List.of(approved(ONE), approved(TWO))));

        assertAll(
                () -> assertEquals(
                        baseline.inputStateHash(),
                        factory.create(request(List.of(approved(TWO), approved(ONE)))).inputStateHash()),
                () -> assertNotEquals(
                        baseline.inputStateHash(),
                        factory.create(request(OTHER_BOT, PARTITION, EVENT, EVALUATION, SOURCE,
                                List.of(approved(ONE), approved(TWO)))).inputStateHash()),
                () -> assertNotEquals(
                        baseline.inputStateHash(),
                        factory.create(request(BOT, OTHER_PARTITION, EVENT, EVALUATION, SOURCE,
                                List.of(approved(ONE), approved(TWO)))).inputStateHash()),
                () -> assertNotEquals(
                        baseline.inputStateHash(),
                        factory.create(request(BOT, PARTITION, OTHER_EVENT, EVALUATION, SOURCE,
                                List.of(approved(ONE), approved(TWO)))).inputStateHash()),
                () -> assertNotEquals(
                        baseline.inputStateHash(),
                        factory.create(request(BOT, PARTITION, EVENT, EVALUATION, OTHER_SOURCE,
                                List.of(approved(ONE), approved(TWO)))).inputStateHash()),
                () -> assertNotEquals(
                        baseline.inputStateHash(),
                        factory.create(request(List.of(approved(ONE), approved(THREE)))).inputStateHash()));
    }

    /**
     * A batch that decided differently is different work even though its identity is unchanged. This
     * is what stops a redelivery check from accepting a divergent decision as a replay.
     */
    @Test
    void aChangedDecisionChangesTheInputAndResultHashesButNotTheIdentity() {
        OrderIntentBatch approvedBatch = factory.create(request(List.of(approved(ONE))));
        OrderIntentBatch rejectedBatch = factory.create(request(List.of(rejected(ONE))));

        assertAll(
                () -> assertEquals(approvedBatch.batchId(), rejectedBatch.batchId()),
                () -> assertEquals(intentFor(approvedBatch, ONE), intentFor(rejectedBatch, ONE)),
                () -> assertNotEquals(approvedBatch.inputStateHash(), rejectedBatch.inputStateHash()),
                () -> assertNotEquals(approvedBatch.resultHash(), rejectedBatch.resultHash()));
    }

    @Test
    void requestCopiesAndSortsCandidates() {
        ArrayList<OrderIntentRequest> intents =
                new ArrayList<>(List.of(approved(TWO), approved(ONE)));
        OrderIntentBatchRequest request = request(intents);
        intents.clear();

        assertAll(
                () -> assertEquals(
                        List.of(ONE, TWO),
                        request.intents().stream().map(OrderIntentRequest::candidateId).toList()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> request.intents().add(approved(THREE))));
    }

    @Test
    void batchCopiesAndExposesAnImmutableIntentList() {
        OrderIntentBatch batch = factory.create(request(List.of(approved(ONE), approved(TWO))));

        assertAll(
                () -> assertEquals(2, batch.intents().size()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> batch.intents().add(batch.intents().getFirst())));
    }

    @Test
    void requestRejectsMissingScopeNullElementsAndDuplicates() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> request(
                        null, PARTITION, EVENT, EVALUATION, SOURCE, List.of(approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(
                        BOT, null, EVENT, EVALUATION, SOURCE, List.of(approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(
                        BOT, PARTITION, null, EVALUATION, SOURCE, List.of(approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(
                        BOT, PARTITION, EVENT, null, SOURCE, List.of(approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(
                        BOT, PARTITION, EVENT, EVALUATION, null, List.of(approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> request(Collections.singletonList(null))),
                () -> assertThrows(IllegalArgumentException.class, () -> request(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> request(List.of(approved(ONE), approved(ONE)))),
                () -> assertThrows(IllegalArgumentException.class, () -> factory.create(null)),
                () -> assertDoesNotThrow(() -> request(List.of(approved(ONE), approved(TWO)))));
    }

    @Test
    void batchRejectsIdsThatAreNotRfc4122VersionFive() {
        OrderIntent valid = factory.create(request(List.of(approved(ONE)))).intents().getFirst();
        UUID versionFiveWithNonRfcVariant = UUID.fromString("80000000-0000-5000-0000-000000000000");

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OrderIntent(UUID.randomUUID(), valid.intentKey(), valid.request())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderIntent(
                        versionFiveWithNonRfcVariant, valid.intentKey(), valid.request())),
                () -> assertThrows(IllegalArgumentException.class, () -> batchOf(UUID.randomUUID(), valid)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> batchOf(versionFiveWithNonRfcVariant, valid)),
                () -> assertDoesNotThrow(() -> batchOf(BATCH_ID, valid)));
    }

    @Test
    void intentKeyMustNameItsSourceCandidate() {
        OrderIntent valid = factory.create(request(List.of(approved(ONE)))).intents().getFirst();

        assertThrows(
                IllegalArgumentException.class,
                () -> new OrderIntent(valid.intentId(), "candidate:" + TWO, valid.request()));
    }

    private static OrderIntentBatch batchOf(UUID batchId, OrderIntent intent) {
        return new OrderIntentBatch(
                batchId, BOT, PARTITION, EVENT, OrderIntentOrigin.FLOW_EVALUATION, EVALUATION,
                HASH, HASH, "rules:v1", HASH, AT, List.of(intent));
    }

    private static OrderIntentRequest approved(UUID candidateId) {
        return new OrderIntentRequest(
                candidateId, FLOW, INSTRUMENT, OrderSide.BUY, OrderPositionEffect.INCREASE_LONG,
                OrderType.MARKET, TimeInForce.DAY, new BigDecimal("2"), null, null, null,
                IntentDecision.APPROVED, "ELIGIBLE", new BigDecimal("2"));
    }

    private static OrderIntentRequest rejected(UUID candidateId) {
        return new OrderIntentRequest(
                candidateId, FLOW, INSTRUMENT, OrderSide.BUY, OrderPositionEffect.INCREASE_LONG,
                OrderType.MARKET, TimeInForce.DAY, new BigDecimal("2"), null, null, null,
                IntentDecision.REJECTED, "RISK_LIMIT_EXCEEDED", null);
    }

    private static OrderIntentBatchRequest request(List<OrderIntentRequest> intents) {
        return request(BOT, PARTITION, EVENT, EVALUATION, SOURCE, intents);
    }

    private static OrderIntentBatchRequest request(
            UUID botId, UUID partitionId, UUID sourceEventId, UUID evaluationId, UUID sourceBatchId,
            List<OrderIntentRequest> intents) {
        return new OrderIntentBatchRequest(
                botId, partitionId, sourceEventId, evaluationId, sourceBatchId, AT, intents);
    }

    private static UUID intentFor(OrderIntentBatch batch, UUID candidateId) {
        return batch.intents().stream()
                .filter(intent -> intent.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow()
                .intentId();
    }

    private static final UUID BOT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_BOT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARTITION = UUID.fromString("1a000000-0000-0000-0000-00000000001a");
    private static final UUID OTHER_PARTITION = UUID.fromString("1b000000-0000-0000-0000-00000000001b");
    private static final UUID EVENT = UUID.fromString("1c000000-0000-0000-0000-00000000001c");
    private static final UUID OTHER_EVENT = UUID.fromString("1d000000-0000-0000-0000-00000000001d");
    private static final UUID EVALUATION = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_EVALUATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_SOURCE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ONE = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID TWO = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID THREE = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID FLOW = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final UUID INSTRUMENT = UUID.fromString("80000000-0000-0000-0000-000000000008");
    private static final UUID BATCH_ID = UUID.fromString("80000000-0000-5000-8000-000000000008");
    private static final Instant AT = Instant.parse("2026-08-02T09:00:00Z");
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
