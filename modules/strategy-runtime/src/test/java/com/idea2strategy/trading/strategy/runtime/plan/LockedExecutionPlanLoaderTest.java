package com.idea2strategy.trading.strategy.runtime.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LockedExecutionPlanLoaderTest {
    private static final UUID BOT_ID = UUID.fromString("b274523a-e318-4b7b-81bd-d3458738a690");
    private static final UUID RELEASE_ID = UUID.fromString("314d3ed1-b7ca-4432-94e3-a13b53ed122d");
    private static final FeatureRequirement RSI = new FeatureRequirement("rsi", "1.0.0");

    @Test
    void loadsOnlyAnUntamperedLockedPlanWithMatchingRuntimeState() {
        Map<String, String> mutableState = new LinkedHashMap<>(Map.of("lastSignal", "NONE"));
        ReleasedExecutionPlanSnapshot plan = lockedPlan("{\"type\":\"basic\"}", Set.of(RSI));
        RuntimeStateSnapshot state = runtimeState(mutableState);
        LockedExecutionPlanLoader loader = loader(new ExecutionPlanSourceSnapshot(plan, state));

        LoadedExecutionPlan loaded = loader.load(BOT_ID);
        mutableState.put("lastSignal", "BUY");

        assertEquals(BOT_ID, loaded.botId());
        assertEquals(RELEASE_ID, loaded.releaseId());
        assertEquals("{\"type\":\"basic\"}", loaded.planPayload());
        assertEquals(Map.of("lastSignal", "NONE"), loaded.runtimeState());
        assertThrows(UnsupportedOperationException.class, () -> loaded.runtimeState().put("other", "value"));
    }

    @Test
    void rejectsPlanPayloadChangedAfterItsDigestWasProduced() {
        ReleasedExecutionPlanSnapshot signed = lockedPlan("{\"rule\":\"price>10\"}", Set.of(RSI));
        ReleasedExecutionPlanSnapshot tampered = new ReleasedExecutionPlanSnapshot(
                signed.botId(),
                signed.releaseId(),
                signed.locked(),
                signed.planSchemaVersion(),
                signed.requiredFeatures(),
                "{\"rule\":\"price>999\"}",
                signed.integritySha256());

        ExecutionPlanLoadException exception = assertThrows(
                ExecutionPlanLoadException.class,
                () -> loader(new ExecutionPlanSourceSnapshot(tampered, runtimeState(Map.of()))).load(BOT_ID));

        assertEquals(ExecutionPlanLoadFailure.PLAN_INTEGRITY_MISMATCH, exception.failure());
    }

    @Test
    void rejectsUnlockedPlansBeforeCreatingARuntime() {
        ReleasedExecutionPlanSnapshot signed = lockedPlan("{}", Set.of(RSI));
        ReleasedExecutionPlanSnapshot unlocked = new ReleasedExecutionPlanSnapshot(
                signed.botId(),
                signed.releaseId(),
                false,
                signed.planSchemaVersion(),
                signed.requiredFeatures(),
                signed.planPayload(),
                ExecutionPlanIntegrity.planSha256(
                        signed.botId(), signed.releaseId(), false, signed.planSchemaVersion(),
                        signed.requiredFeatures(), signed.planPayload()));

        ExecutionPlanLoadException exception = assertThrows(
                ExecutionPlanLoadException.class,
                () -> loader(new ExecutionPlanSourceSnapshot(unlocked, runtimeState(Map.of()))).load(BOT_ID));

        assertEquals(ExecutionPlanLoadFailure.PLAN_NOT_LOCKED, exception.failure());
    }

    @Test
    void rejectsEveryUnsupportedVersionWithoutFallback() {
        ReleasedExecutionPlanSnapshot wrongPlanSchema = lockedPlan("{}", "basic-plan-v2", Set.of(RSI));
        assertFailure(wrongPlanSchema, runtimeState(Map.of()), ExecutionPlanLoadFailure.PLAN_SCHEMA_VERSION_MISMATCH);

        ReleasedExecutionPlanSnapshot wrongFeature = lockedPlan(
                "{}", Set.of(new FeatureRequirement("rsi", "2.0.0")));
        assertFailure(wrongFeature, runtimeState(Map.of()), ExecutionPlanLoadFailure.FEATURE_VERSION_MISMATCH);

        RuntimeStateSnapshot wrongRuntimeSchema = runtimeState("runtime-v2", Map.of());
        assertFailure(lockedPlan("{}", Set.of(RSI)), wrongRuntimeSchema,
                ExecutionPlanLoadFailure.RUNTIME_SCHEMA_VERSION_MISMATCH);
    }

    @Test
    void rejectsRuntimeStateForAnotherBotOrRelease() {
        RuntimeStateSnapshot otherBot = runtimeState(
                UUID.fromString("23354f1e-d4c2-428f-991a-f91bc2114107"), RELEASE_ID, "runtime-v1", Map.of());
        assertFailure(lockedPlan("{}", Set.of(RSI)), otherBot,
                ExecutionPlanLoadFailure.RUNTIME_IDENTITY_MISMATCH);

        RuntimeStateSnapshot otherRelease = runtimeState(
                BOT_ID, UUID.fromString("f06f72ae-d805-49d7-b18d-ec23d9de8fed"), "runtime-v1", Map.of());
        assertFailure(lockedPlan("{}", Set.of(RSI)), otherRelease,
                ExecutionPlanLoadFailure.RUNTIME_IDENTITY_MISMATCH);
    }

    @Test
    void rejectsASignedPlanReturnedForAnotherBot() {
        UUID otherBotId = UUID.fromString("23354f1e-d4c2-428f-991a-f91bc2114107");
        ReleasedExecutionPlanSnapshot otherBotPlan = ReleasedExecutionPlanSnapshot.locked(
                otherBotId, RELEASE_ID, "basic-plan-v1", Set.of(RSI), "{}");

        ExecutionPlanLoadException exception = assertThrows(
                ExecutionPlanLoadException.class,
                () -> loader(new ExecutionPlanSourceSnapshot(otherBotPlan, runtimeState(Map.of()))).load(BOT_ID));

        assertEquals(ExecutionPlanLoadFailure.PLAN_IDENTITY_MISMATCH, exception.failure());
    }

    @Test
    void rejectsTamperedRuntimeStateAndMissingSnapshots() {
        RuntimeStateSnapshot signed = runtimeState(Map.of("position", "10"));
        RuntimeStateSnapshot tampered = new RuntimeStateSnapshot(
                signed.botId(), signed.releaseId(), signed.runtimeSchemaVersion(), signed.sequence(),
                Map.of("position", "1000"), signed.integritySha256());
        assertFailure(lockedPlan("{}", Set.of(RSI)), tampered,
                ExecutionPlanLoadFailure.RUNTIME_INTEGRITY_MISMATCH);

        ExecutionPlanLoadException missing = assertThrows(
                ExecutionPlanLoadException.class,
                () -> new LockedExecutionPlanLoader(
                                botId -> Optional.empty(), compatibility())
                        .load(BOT_ID));
        assertEquals(ExecutionPlanLoadFailure.SNAPSHOT_NOT_FOUND, missing.failure());
    }

    private static void assertFailure(
            ReleasedExecutionPlanSnapshot plan,
            RuntimeStateSnapshot state,
            ExecutionPlanLoadFailure expected) {
        ExecutionPlanLoadException exception = assertThrows(
                ExecutionPlanLoadException.class,
                () -> loader(new ExecutionPlanSourceSnapshot(plan, state)).load(BOT_ID));
        assertEquals(expected, exception.failure());
        assertTrue(exception.getMessage().contains(expected.name()));
    }

    private static LockedExecutionPlanLoader loader(ExecutionPlanSourceSnapshot snapshot) {
        return new LockedExecutionPlanLoader(botId -> Optional.of(snapshot), compatibility());
    }

    private static ExecutionPlanCompatibility compatibility() {
        return new ExecutionPlanCompatibility(
                "basic-plan-v1", "runtime-v1", Map.of("rsi", "1.0.0", "sma", "1.0.0"));
    }

    private static ReleasedExecutionPlanSnapshot lockedPlan(
            String payload, Set<FeatureRequirement> requirements) {
        return lockedPlan(payload, "basic-plan-v1", requirements);
    }

    private static ReleasedExecutionPlanSnapshot lockedPlan(
            String payload, String schemaVersion, Set<FeatureRequirement> requirements) {
        return ReleasedExecutionPlanSnapshot.locked(
                BOT_ID, RELEASE_ID, schemaVersion, requirements, payload);
    }

    private static RuntimeStateSnapshot runtimeState(Map<String, String> values) {
        return runtimeState("runtime-v1", values);
    }

    private static RuntimeStateSnapshot runtimeState(String schemaVersion, Map<String, String> values) {
        return runtimeState(BOT_ID, RELEASE_ID, schemaVersion, values);
    }

    private static RuntimeStateSnapshot runtimeState(
            UUID botId, UUID releaseId, String schemaVersion, Map<String, String> values) {
        return RuntimeStateSnapshot.signed(botId, releaseId, schemaVersion, 7, values);
    }
}
