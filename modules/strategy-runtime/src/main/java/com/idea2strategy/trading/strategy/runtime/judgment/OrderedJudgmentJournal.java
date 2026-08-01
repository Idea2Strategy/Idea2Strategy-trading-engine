package com.idea2strategy.trading.strategy.runtime.judgment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrderedJudgmentJournal {
    private final Map<UUID, BotJournal> journals = new ConcurrentHashMap<>();

    public JudgmentEntry append(UUID botId, long expectedLastSequence, JudgmentEventDraft draft) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        if (expectedLastSequence < 0) {
            throw new IllegalArgumentException("expectedLastSequence must not be negative");
        }
        BotJournal journal = journals.computeIfAbsent(botId, ignored -> new BotJournal());
        synchronized (journal) {
            JudgmentEntry existing = journal.entriesById.get(draft.eventId());
            if (existing != null) {
                if (existing.hasSameMeaning(draft)) {
                    return existing;
                }
                throw failure(
                        JudgmentAppendFailure.EVENT_IDENTITY_CONFLICT,
                        "eventId already has different immutable content");
            }
            if (expectedLastSequence != journal.entries.size()) {
                throw failure(
                        JudgmentAppendFailure.STALE_JOURNAL_SEQUENCE,
                        "expected journal sequence does not match the current sequence");
            }

            FirstFailureKey firstFailureKey = firstFailureKey(draft);
            if (firstFailureKey != null && journal.firstFailures.contains(firstFailureKey)) {
                throw failure(
                        JudgmentAppendFailure.DUPLICATE_FIRST_FAILURE,
                        "the evaluation flow and instrument already has an official first failure");
            }

            ProjectedRuntimeState nextRuntimeState = validateRuntimeTransition(journal.runtimeState, draft);
            JudgmentEntry entry = JudgmentEntry.from(journal.entries.size() + 1L, draft);

            journal.entries.add(entry);
            journal.entriesById.put(entry.eventId(), entry);
            if (firstFailureKey != null) {
                journal.firstFailures.add(firstFailureKey);
            }
            journal.runtimeState = nextRuntimeState;
            return entry;
        }
    }

    public BotJudgmentSnapshot snapshot(UUID botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        BotJournal journal = journals.get(botId);
        if (journal == null) {
            return new BotJudgmentSnapshot(
                    botId, 0, List.of(), new ProjectedRuntimeState(0, Map.of()));
        }
        synchronized (journal) {
            return new BotJudgmentSnapshot(
                    botId,
                    journal.entries.size(),
                    journal.entries,
                    journal.runtimeState);
        }
    }

    private static FirstFailureKey firstFailureKey(JudgmentEventDraft draft) {
        if (draft.type() != JudgmentEventType.FIRST_CONDITION_FAILED) {
            return null;
        }
        return new FirstFailureKey(
                draft.subject().evaluationId(),
                draft.subject().flowId().orElseThrow(),
                draft.subject().instrumentId().orElseThrow());
    }

    private static ProjectedRuntimeState validateRuntimeTransition(
            ProjectedRuntimeState current,
            JudgmentEventDraft draft) {
        if (draft.runtimeStateTransition().isEmpty()) {
            return current;
        }
        RuntimeStateTransition transition = draft.runtimeStateTransition().orElseThrow();
        if (transition.fromRevision() != current.revision()) {
            throw failure(
                    JudgmentAppendFailure.RUNTIME_REVISION_MISMATCH,
                    "runtime transition does not start at the projected revision");
        }
        if (transition.toRevision() != transition.fromRevision() + 1) {
            throw failure(
                    JudgmentAppendFailure.RUNTIME_REVISION_GAP,
                    "runtime transition must advance exactly one revision");
        }
        return new ProjectedRuntimeState(
                transition.toRevision(), transition.replacementValues());
    }

    private static JudgmentAppendException failure(JudgmentAppendFailure failure, String message) {
        return new JudgmentAppendException(failure, message);
    }

    private static final class BotJournal {
        private final List<JudgmentEntry> entries = new ArrayList<>();
        private final Map<UUID, JudgmentEntry> entriesById = new HashMap<>();
        private final Set<FirstFailureKey> firstFailures = new HashSet<>();
        private ProjectedRuntimeState runtimeState = new ProjectedRuntimeState(0, Map.of());
    }

    private record FirstFailureKey(UUID evaluationId, String flowId, UUID instrumentId) {
    }
}
