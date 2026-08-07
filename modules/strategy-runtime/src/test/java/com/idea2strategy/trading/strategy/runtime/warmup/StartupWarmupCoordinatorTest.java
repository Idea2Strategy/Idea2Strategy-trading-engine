package com.idea2strategy.trading.strategy.runtime.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StartupWarmupCoordinatorTest {
    private static final UUID BOT_ID = UUID.fromString("b274523a-e318-4b7b-81bd-d3458738a690");
    private static final UUID RELEASE_ID = UUID.fromString("314d3ed1-b7ca-4432-94e3-a13b53ed122d");
    private static final Instant STARTUP_TIME = Instant.parse("2026-08-01T14:30:00Z");

    @Test
    void blocksStartupWhenARequiredInstrumentHasTooFewObservations() {
        WarmupRequirement rsi = new WarmupRequirement(
                "rsi-entry", "rsi", "1.0.0", Set.of("AAPL"), "PT1M", 3);
        WarmupDataSnapshot snapshot = snapshot(Map.of(
                rsi.requirementId(), series(rsi, List.of(
                        observation("AAPL", "2026-08-01T14:28:00Z", "45.1"),
                        observation("AAPL", "2026-08-01T14:29:00Z", "46.2")))));
        StartupWarmupCoordinator coordinator = coordinator(request -> Optional.of(snapshot));

        WarmupException exception = assertThrows(
                WarmupException.class,
                () -> coordinator.prepare(new WarmupRequest(BOT_ID, RELEASE_ID, STARTUP_TIME, Set.of(rsi))));

        assertEquals(WarmupFailure.REQUIREMENT_COVERAGE_INSUFFICIENT, exception.failure());
    }

    @Test
    void preparesOnlyTheExactRecentObservationsRequestedFromOneManifest() {
        WarmupRequirement rsi = new WarmupRequirement(
                "rsi-entry", "rsi", "1.0.0", Set.of("AAPL", "MSFT"), "PT1M", 2);
        WarmupFeatureSeries requested = series(rsi, List.of(
                observation("AAPL", "2026-08-01T14:25:00Z", "40.0"),
                observation("AAPL", "2026-08-01T14:28:00Z", "45.1"),
                observation("AAPL", "2026-08-01T14:29:00Z", "46.2"),
                observation("MSFT", "2026-08-01T14:28:00Z", "51.0"),
                observation("MSFT", "2026-08-01T14:29:00Z", "52.0"),
                observation("TSLA", "2026-08-01T14:29:00Z", "63.0")));
        WarmupFeatureSeries unrelated = new WarmupFeatureSeries(
                "sma-exit", "sma", "1.0.0", "PT5M", "manifest-20260801", "a".repeat(64),
                List.of(observation("AAPL", "2026-08-01T14:25:00Z", "190.0")));
        AtomicReference<WarmupRequest> received = new AtomicReference<>();
        StartupWarmupCoordinator coordinator = coordinator(request -> {
            received.set(request);
            return Optional.of(snapshot(Map.of(rsi.requirementId(), requested, "sma-exit", unrelated)));
        });
        WarmupRequest request = new WarmupRequest(BOT_ID, RELEASE_ID, STARTUP_TIME, Set.of(rsi));

        PreparedWarmup prepared = coordinator.prepare(request);

        assertEquals(request, received.get());
        assertEquals("manifest-20260801", prepared.manifestId());
        assertEquals(7, prepared.manifestRevision());
        assertEquals(Set.of("rsi-entry"), prepared.seriesByRequirementId().keySet());
        List<FeatureObservation> observations = prepared.seriesByRequirementId().get("rsi-entry").observations();
        assertEquals(4, observations.size());
        assertFalse(observations.stream().anyMatch(value -> value.instrument().equals("TSLA")));
        assertFalse(observations.stream().anyMatch(value -> value.value().equals(new BigDecimal("40.0"))));
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.seriesByRequirementId().put("other", unrelated));
        assertThrows(UnsupportedOperationException.class,
                () -> observations.add(observation("AAPL", "2026-08-01T14:20:00Z", "1")));
    }

    @Test
    void blocksUnavailableOrIncompatibleOrCorruptedManifests() {
        assertManifestFailure(manifest(
                DatasetManifestStatus.BUILDING, "dataset-manifest-v1", object("b", "b", "feature-object-v1")),
                WarmupFailure.MANIFEST_UNAVAILABLE);
        assertManifestFailure(manifest(
                DatasetManifestStatus.AVAILABLE, "dataset-manifest-v2", object("b", "b", "feature-object-v1")),
                WarmupFailure.MANIFEST_SCHEMA_MISMATCH);
        assertManifestFailure(manifest(
                DatasetManifestStatus.AVAILABLE, "dataset-manifest-v1", object("b", "b", "feature-object-v2")),
                WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH);
        assertManifestFailure(manifest(
                DatasetManifestStatus.AVAILABLE, "dataset-manifest-v1", object("b", "c", "feature-object-v1")),
                WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH);
    }

    @Test
    void blocksMissingOrMismatchedRequirementSeries() {
        WarmupRequirement requirement = requirement();
        assertSeriesFailure(requirement, null, WarmupFailure.REQUIREMENT_MISSING);
        assertSeriesFailure(requirement, new WarmupFeatureSeries(
                requirement.requirementId(), "sma", requirement.featureVersion(), requirement.resolution(),
                "manifest-20260801", "a".repeat(64), completeObservations()),
                WarmupFailure.REQUIREMENT_FEATURE_MISMATCH);
        assertSeriesFailure(requirement, new WarmupFeatureSeries(
                requirement.requirementId(), requirement.featureId(), "2.0.0", requirement.resolution(),
                "manifest-20260801", "a".repeat(64), completeObservations()),
                WarmupFailure.REQUIREMENT_VERSION_MISMATCH);
        assertSeriesFailure(requirement, new WarmupFeatureSeries(
                requirement.requirementId(), requirement.featureId(), requirement.featureVersion(), "PT5M",
                "manifest-20260801", "a".repeat(64), completeObservations()),
                WarmupFailure.REQUIREMENT_RESOLUTION_MISMATCH);
        assertSeriesFailure(requirement, new WarmupFeatureSeries(
                requirement.requirementId(), requirement.featureId(), requirement.featureVersion(),
                requirement.resolution(), "another-manifest", "a".repeat(64), completeObservations()),
                WarmupFailure.REQUIREMENT_MANIFEST_MISMATCH);
    }

    @Test
    void blocksFutureOrDuplicateObservationsAndMissingSnapshots() {
        WarmupRequirement requirement = requirement();
        assertSeriesFailure(requirement, series(requirement, List.of(
                observation("AAPL", "2026-08-01T14:29:00Z", "45"),
                observation("AAPL", "2026-08-01T14:30:00Z", "46"))),
                WarmupFailure.REQUIREMENT_OBSERVATION_OUT_OF_RANGE);
        assertSeriesFailure(requirement, series(requirement, List.of(
                observation("AAPL", "2026-08-01T14:29:00Z", "45"),
                observation("AAPL", "2026-08-01T14:29:00Z", "46"))),
                WarmupFailure.REQUIREMENT_OBSERVATION_DUPLICATE);

        WarmupException missing = assertThrows(WarmupException.class,
                () -> coordinator(request -> Optional.empty()).prepare(request(requirement)));
        assertEquals(WarmupFailure.SNAPSHOT_NOT_FOUND, missing.failure());
        assertTrue(missing.getMessage().contains(BOT_ID.toString()));
    }

    private static StartupWarmupCoordinator coordinator(WarmupDataSource source) {
        return new StartupWarmupCoordinator(source, "dataset-manifest-v1", "feature-object-v1");
    }

    private static WarmupDataSnapshot snapshot(Map<String, WarmupFeatureSeries> series) {
        return new WarmupDataSnapshot(manifest(
                DatasetManifestStatus.AVAILABLE, "dataset-manifest-v1",
                object("b", "b", "feature-object-v1")), series);
    }

    private static DatasetManifestSnapshot manifest(
            DatasetManifestStatus status, String schemaVersion, DatasetObjectSnapshot object) {
        return new DatasetManifestSnapshot(
                "manifest-20260801", "us-equities", 7, status,
                schemaVersion, "a".repeat(64), List.of(object));
    }

    private static DatasetObjectSnapshot object(String declaredHash, String observedHash, String schemaVersion) {
        return new DatasetObjectSnapshot(
                "features/rsi/aapl.parquet", declaredHash.repeat(64), observedHash.repeat(64), schemaVersion);
    }

    private static WarmupFeatureSeries series(
            WarmupRequirement requirement, List<FeatureObservation> observations) {
        return new WarmupFeatureSeries(
                requirement.requirementId(), requirement.featureId(), requirement.featureVersion(),
                requirement.resolution(), "manifest-20260801", "a".repeat(64), observations);
    }

    private static FeatureObservation observation(String instrument, String time, String value) {
        return new FeatureObservation(instrument, Instant.parse(time), new BigDecimal(value));
    }

    private static WarmupRequirement requirement() {
        return new WarmupRequirement("rsi-entry", "rsi", "1.0.0", Set.of("AAPL"), "PT1M", 2);
    }

    private static WarmupRequest request(WarmupRequirement requirement) {
        return new WarmupRequest(BOT_ID, RELEASE_ID, STARTUP_TIME, Set.of(requirement));
    }

    private static List<FeatureObservation> completeObservations() {
        return List.of(
                observation("AAPL", "2026-08-01T14:28:00Z", "45.1"),
                observation("AAPL", "2026-08-01T14:29:00Z", "46.2"));
    }

    private static void assertManifestFailure(DatasetManifestSnapshot manifest, WarmupFailure expected) {
        WarmupRequirement requirement = requirement();
        WarmupDataSnapshot snapshot = new WarmupDataSnapshot(
                manifest, Map.of(requirement.requirementId(), series(requirement, completeObservations())));
        WarmupException exception = assertThrows(
                WarmupException.class, () -> coordinator(value -> Optional.of(snapshot)).prepare(request(requirement)));
        assertEquals(expected, exception.failure());
    }

    private static void assertSeriesFailure(
            WarmupRequirement requirement, WarmupFeatureSeries series, WarmupFailure expected) {
        Map<String, WarmupFeatureSeries> seriesByRequirement = series == null
                ? Map.of()
                : Map.of(requirement.requirementId(), series);
        WarmupException exception = assertThrows(WarmupException.class,
                () -> coordinator(value -> Optional.of(snapshot(seriesByRequirement))).prepare(request(requirement)));
        assertEquals(expected, exception.failure());
    }

    @Test
    void skipsTheManifestSourceWhenAPlanUsesOnlyDirectOperations() {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        StartupWarmupCoordinator coordinator = new StartupWarmupCoordinator(request -> {
            called.set(true);
            return java.util.Optional.empty();
        }, "dataset-manifest-v1", "feature-object-v1");

        PreparedWarmup prepared = coordinator.prepare(
                new WarmupRequest(BOT_ID, RELEASE_ID, STARTUP_TIME, Set.of()));

        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertFalse(called.get()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        prepared.seriesByRequirementId().isEmpty()));
    }
}
