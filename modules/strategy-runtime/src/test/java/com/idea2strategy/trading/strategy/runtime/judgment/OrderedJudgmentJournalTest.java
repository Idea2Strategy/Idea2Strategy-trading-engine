package com.idea2strategy.trading.strategy.runtime.judgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderedJudgmentJournalTest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000501");
    private static final UUID EVALUATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000502");
    private static final UUID INSTRUMENT_A = UUID.fromString("00000000-0000-4000-8000-000000000503");
    private static final UUID INSTRUMENT_B = UUID.fromString("00000000-0000-4000-8000-000000000504");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-4000-8000-000000000505");

    @Test
    void recordsJudgmentsInOrderAndProjectsRuntimeStateAtomically() {
        OrderedJudgmentJournal journal = new OrderedJudgmentJournal();
        JudgmentEventDraft conditionPassed = event(
                "10000000-0000-4000-8000-000000000001",
                JudgmentEventType.CONDITION_SATISFIED,
                subject("buy-flow", INSTRUMENT_A, null),
                Map.of("stepId", "price-above-average", "reasonCode", "CONDITION_TRUE"));
        JudgmentEventDraft firstFailure = event(
                "10000000-0000-4000-8000-000000000002",
                JudgmentEventType.FIRST_CONDITION_FAILED,
                subject("buy-flow", INSTRUMENT_B, null),
                Map.of("stepId", "volume-threshold", "reasonCode", "CONDITION_FALSE"));
        JudgmentEventDraft candidateCreated = event(
                "10000000-0000-4000-8000-000000000003",
                JudgmentEventType.CANDIDATE_CREATED,
                subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                Map.of("side", "BUY", "allocation", "1/1"));
        JudgmentEventDraft stateChanged = runtimeChange(
                "10000000-0000-4000-8000-000000000004",
                0,
                1,
                Map.of("lastEvaluationId", EVALUATION_ID.toString(), "status", "RUNNING"));

        JudgmentEntry first = journal.append(BOT_ID, 0, conditionPassed);
        journal.append(BOT_ID, 1, firstFailure);
        journal.append(BOT_ID, 2, candidateCreated);
        journal.append(BOT_ID, 3, stateChanged);
        JudgmentEntry retried = journal.append(BOT_ID, 0, conditionPassed);
        BotJudgmentSnapshot snapshot = journal.snapshot(BOT_ID);

        assertSame(first, retried);
        assertEquals(List.of(1L, 2L, 3L, 4L), snapshot.entries().stream().map(JudgmentEntry::sequence).toList());
        assertEquals(
                List.of(
                        JudgmentEventType.CONDITION_SATISFIED,
                        JudgmentEventType.FIRST_CONDITION_FAILED,
                        JudgmentEventType.CANDIDATE_CREATED,
                        JudgmentEventType.RUNTIME_STATE_CHANGED),
                snapshot.entries().stream().map(JudgmentEntry::type).toList());
        assertEquals(4, snapshot.lastSequence());
        assertEquals(1, snapshot.runtimeState().revision());
        assertEquals(Map.of("lastEvaluationId", EVALUATION_ID.toString(), "status", "RUNNING"),
                snapshot.runtimeState().values());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().add(first));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.runtimeState().values().put("status", "STOPPED"));
    }

    @Test
    void rejectsIdentityConflictsAndStaleSequenceWithoutRewritingEvidence() {
        OrderedJudgmentJournal journal = new OrderedJudgmentJournal();
        JudgmentEventDraft original = event(
                "20000000-0000-4000-8000-000000000001",
                JudgmentEventType.RULE_APPLIED,
                subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                Map.of("rule", "equal-allocation"));
        journal.append(BOT_ID, 0, original);
        BotJudgmentSnapshot before = journal.snapshot(BOT_ID);

        JudgmentAppendException identityConflict = assertThrows(JudgmentAppendException.class, () -> journal.append(
                BOT_ID,
                1,
                event(
                        "20000000-0000-4000-8000-000000000001",
                        JudgmentEventType.CANDIDATE_REJECTED,
                        subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                        Map.of("reason", "CHANGED_MEANING"))));
        JudgmentAppendException staleSequence = assertThrows(JudgmentAppendException.class, () -> journal.append(
                BOT_ID,
                0,
                event(
                        "20000000-0000-4000-8000-000000000002",
                        JudgmentEventType.CANDIDATE_REJECTED,
                        subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                        Map.of("reason", "STALE_INPUT"))));

        assertEquals(JudgmentAppendFailure.EVENT_IDENTITY_CONFLICT, identityConflict.failure());
        assertEquals(JudgmentAppendFailure.STALE_JOURNAL_SEQUENCE, staleSequence.failure());
        assertEquals(before, journal.snapshot(BOT_ID));
    }

    @Test
    void keepsOnlyOneOfficialFirstFailurePerEvaluationFlowAndInstrument() {
        OrderedJudgmentJournal journal = new OrderedJudgmentJournal();
        journal.append(BOT_ID, 0, event(
                "30000000-0000-4000-8000-000000000001",
                JudgmentEventType.FIRST_CONDITION_FAILED,
                subject("buy-flow", INSTRUMENT_A, null),
                Map.of("stepId", "first", "reasonCode", "FALSE")));

        JudgmentAppendException duplicate = assertThrows(JudgmentAppendException.class, () -> journal.append(
                BOT_ID,
                1,
                event(
                        "30000000-0000-4000-8000-000000000002",
                        JudgmentEventType.FIRST_CONDITION_FAILED,
                        subject("buy-flow", INSTRUMENT_A, null),
                        Map.of("stepId", "later", "reasonCode", "FALSE"))));
        JudgmentEntry unrelated = journal.append(BOT_ID, 1, event(
                "30000000-0000-4000-8000-000000000003",
                JudgmentEventType.FIRST_CONDITION_FAILED,
                subject("buy-flow", INSTRUMENT_B, null),
                Map.of("stepId", "other-instrument", "reasonCode", "FALSE")));

        assertEquals(JudgmentAppendFailure.DUPLICATE_FIRST_FAILURE, duplicate.failure());
        assertEquals(2, unrelated.sequence());
    }

    @Test
    void rejectsStaleRuntimeTransitionsWithoutRecordingAnEvent() {
        OrderedJudgmentJournal journal = new OrderedJudgmentJournal();
        journal.append(BOT_ID, 0, runtimeChange(
                "40000000-0000-4000-8000-000000000001", 0, 1, Map.of("status", "RUNNING")));

        JudgmentAppendException staleRuntime = assertThrows(JudgmentAppendException.class, () -> journal.append(
                BOT_ID,
                1,
                runtimeChange(
                        "40000000-0000-4000-8000-000000000002", 0, 1, Map.of("status", "STOPPING"))));

        assertEquals(JudgmentAppendFailure.RUNTIME_REVISION_MISMATCH, staleRuntime.failure());
        assertEquals(1, journal.snapshot(BOT_ID).entries().size());
        assertEquals(Map.of("status", "RUNNING"), journal.snapshot(BOT_ID).runtimeState().values());
    }

    @Test
    void recordsExternalBudgetAndReductionFactsWithoutDerivingValues() {
        OrderedJudgmentJournal journal = new OrderedJudgmentJournal();
        Map<String, String> budgetFact = Map.of(
                "source", "COM-F",
                "budgetVersion", "budget-v7",
                "available", "1000.005",
                "reserved", "125.125");
        Map<String, String> reductionFact = Map.of(
                "source", "COM-F",
                "candidateId", CANDIDATE_ID.toString(),
                "originalQuantity", "10.75",
                "reducedQuantity", "8.125",
                "rule", "portfolio-risk-v3");

        journal.append(BOT_ID, 0, event(
                "50000000-0000-4000-8000-000000000001",
                JudgmentEventType.BUDGET_CALCULATED,
                subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                budgetFact));
        journal.append(BOT_ID, 1, event(
                "50000000-0000-4000-8000-000000000002",
                JudgmentEventType.CANDIDATE_REDUCED,
                subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                reductionFact));

        assertEquals(budgetFact, journal.snapshot(BOT_ID).entries().get(0).evidence());
        assertEquals(reductionFact, journal.snapshot(BOT_ID).entries().get(1).evidence());
    }

    @Test
    void restoresAValidatedSnapshotAndContinuesAtTheNextSequence() {
        OrderedJudgmentJournal original = new OrderedJudgmentJournal();
        original.append(BOT_ID, 0, event(
                "60000000-0000-4000-8000-000000000001",
                JudgmentEventType.CONDITION_SATISFIED,
                subject("buy-flow", INSTRUMENT_A, null),
                Map.of("stepId", "price-above-average")));
        original.append(BOT_ID, 1, runtimeChange(
                "60000000-0000-4000-8000-000000000002",
                0,
                1,
                Map.of("status", "RUNNING")));

        OrderedJudgmentJournal restored = new OrderedJudgmentJournal();
        restored.restore(original.snapshot(BOT_ID));
        JudgmentEntry continued = restored.append(BOT_ID, 2, event(
                "60000000-0000-4000-8000-000000000003",
                JudgmentEventType.CANDIDATE_CREATED,
                subject("buy-flow", INSTRUMENT_A, CANDIDATE_ID),
                Map.of("side", "BUY")));

        assertEquals(3, continued.sequence());
        assertEquals(original.snapshot(BOT_ID).entries(), restored.snapshot(BOT_ID).entries().subList(0, 2));
        assertEquals(1, restored.snapshot(BOT_ID).runtimeState().revision());
    }

    @Test
    void rejectsACorruptSnapshotAtomically() {
        OrderedJudgmentJournal restored = new OrderedJudgmentJournal();
        JudgmentEntry gap = new JudgmentEntry(
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                2,
                JudgmentEventType.CONDITION_SATISFIED,
                subject("buy-flow", INSTRUMENT_A, null),
                Map.of("stepId", "price-above-average"),
                Optional.empty());
        BotJudgmentSnapshot corrupt = new BotJudgmentSnapshot(
                BOT_ID, 1, List.of(gap), new ProjectedRuntimeState(0, Map.of()));

        JudgmentAppendException failure = assertThrows(
                JudgmentAppendException.class,
                () -> restored.restore(corrupt));

        assertEquals(JudgmentAppendFailure.SNAPSHOT_INVALID, failure.failure());
        assertEquals(new BotJudgmentSnapshot(
                BOT_ID, 0, List.of(), new ProjectedRuntimeState(0, Map.of())), restored.snapshot(BOT_ID));
    }

    @Test
    void rejectsDuplicatedEvidenceAndAnInconsistentProjectionDuringRestore() {
        JudgmentEntry firstFailure = entry(
                "71000000-0000-4000-8000-000000000001",
                1,
                JudgmentEventType.FIRST_CONDITION_FAILED,
                subject("buy-flow", INSTRUMENT_A, null));
        JudgmentEntry duplicateIdentity = entry(
                "71000000-0000-4000-8000-000000000001",
                2,
                JudgmentEventType.CONDITION_SATISFIED,
                subject("buy-flow", INSTRUMENT_B, null));
        JudgmentEntry duplicateFirstFailure = entry(
                "71000000-0000-4000-8000-000000000002",
                2,
                JudgmentEventType.FIRST_CONDITION_FAILED,
                subject("buy-flow", INSTRUMENT_A, null));

        assertInvalidSnapshot(new BotJudgmentSnapshot(
                BOT_ID, 2, List.of(firstFailure, duplicateIdentity), new ProjectedRuntimeState(0, Map.of())));
        assertInvalidSnapshot(new BotJudgmentSnapshot(
                BOT_ID, 2, List.of(firstFailure, duplicateFirstFailure), new ProjectedRuntimeState(0, Map.of())));
        assertInvalidSnapshot(new BotJudgmentSnapshot(
                BOT_ID,
                1,
                List.of(entry(
                        "71000000-0000-4000-8000-000000000003",
                        1,
                        JudgmentEventType.CONDITION_SATISFIED,
                        subject("buy-flow", INSTRUMENT_A, null))),
                new ProjectedRuntimeState(1, Map.of("status", "RUNNING"))));
    }

    @Test
    void neverOverwritesAnExistingJournalDuringRestore() {
        OrderedJudgmentJournal target = new OrderedJudgmentJournal();
        target.append(BOT_ID, 0, event(
                "72000000-0000-4000-8000-000000000001",
                JudgmentEventType.CONDITION_SATISFIED,
                subject("buy-flow", INSTRUMENT_A, null),
                Map.of("source", "live")));
        BotJudgmentSnapshot before = target.snapshot(BOT_ID);

        JudgmentAppendException failure = assertThrows(
                JudgmentAppendException.class,
                () -> target.restore(new BotJudgmentSnapshot(
                        BOT_ID, 0, List.of(), new ProjectedRuntimeState(0, Map.of()))));

        assertEquals(JudgmentAppendFailure.RESTORE_TARGET_NOT_EMPTY, failure.failure());
        assertEquals(before, target.snapshot(BOT_ID));
    }

    private static void assertInvalidSnapshot(BotJudgmentSnapshot snapshot) {
        OrderedJudgmentJournal target = new OrderedJudgmentJournal();
        JudgmentAppendException failure = assertThrows(
                JudgmentAppendException.class, () -> target.restore(snapshot));
        assertEquals(JudgmentAppendFailure.SNAPSHOT_INVALID, failure.failure());
        assertEquals(0, target.snapshot(BOT_ID).lastSequence());
    }

    private static JudgmentEntry entry(
            String eventId,
            long sequence,
            JudgmentEventType type,
            JudgmentSubject subject) {
        return new JudgmentEntry(
                UUID.fromString(eventId), sequence, type, subject, Map.of("source", "snapshot"), Optional.empty());
    }

    private static JudgmentEventDraft event(
            String eventId,
            JudgmentEventType type,
            JudgmentSubject subject,
            Map<String, String> evidence) {
        return new JudgmentEventDraft(
                UUID.fromString(eventId), type, subject, evidence, Optional.empty());
    }

    private static JudgmentEventDraft runtimeChange(
            String eventId,
            long fromRevision,
            long toRevision,
            Map<String, String> values) {
        return new JudgmentEventDraft(
                UUID.fromString(eventId),
                JudgmentEventType.RUNTIME_STATE_CHANGED,
                subject(null, null, null),
                Map.of("reason", "evaluation-completed"),
                Optional.of(new RuntimeStateTransition(fromRevision, toRevision, values)));
    }

    private static JudgmentSubject subject(String flowId, UUID instrumentId, UUID candidateId) {
        return new JudgmentSubject(
                EVALUATION_ID,
                Optional.ofNullable(flowId),
                Optional.ofNullable(instrumentId),
                Optional.ofNullable(candidateId));
    }
}
