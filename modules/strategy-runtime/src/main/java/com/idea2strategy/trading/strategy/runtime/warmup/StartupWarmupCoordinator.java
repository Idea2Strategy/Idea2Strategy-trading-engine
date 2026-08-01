package com.idea2strategy.trading.strategy.runtime.warmup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StartupWarmupCoordinator {
    private static final Comparator<FeatureObservation> BY_TIME = Comparator.comparing(FeatureObservation::observedAt);

    private final WarmupDataSource source;
    private final String expectedManifestSchemaVersion;
    private final String expectedObjectSchemaVersion;

    public StartupWarmupCoordinator(
            WarmupDataSource source,
            String expectedManifestSchemaVersion,
            String expectedObjectSchemaVersion) {
        this.source = Objects.requireNonNull(source, "source");
        this.expectedManifestSchemaVersion = WarmupValueValidation.requireText(
                expectedManifestSchemaVersion, "expectedManifestSchemaVersion");
        this.expectedObjectSchemaVersion = WarmupValueValidation.requireText(
                expectedObjectSchemaVersion, "expectedObjectSchemaVersion");
    }

    public PreparedWarmup prepare(WarmupRequest request) {
        Objects.requireNonNull(request, "request");
        WarmupDataSnapshot snapshot = source.load(request)
                .orElseThrow(() -> failure(WarmupFailure.SNAPSHOT_NOT_FOUND, request.botId().toString()));
        DatasetManifestSnapshot manifest = snapshot.manifest();
        validateManifest(manifest);

        Map<String, WarmupFeatureSeries> prepared = new HashMap<>();
        for (WarmupRequirement requirement : request.requirements()) {
            WarmupFeatureSeries series = snapshot.seriesByRequirementId().get(requirement.requirementId());
            if (series == null) {
                throw failure(WarmupFailure.REQUIREMENT_MISSING, requirement.requirementId());
            }
            validateSeriesIdentity(requirement, series, manifest);
            prepared.put(requirement.requirementId(), selectRequiredObservations(request, requirement, series));
        }

        return new PreparedWarmup(
                manifest.manifestId(), manifest.datasetId(), manifest.revision(), manifest.datasetHash(), prepared);
    }

    private void validateManifest(DatasetManifestSnapshot manifest) {
        if (manifest.status() != DatasetManifestStatus.AVAILABLE) {
            throw failure(WarmupFailure.MANIFEST_UNAVAILABLE, manifest.status().name());
        }
        if (!manifest.schemaVersion().equals(expectedManifestSchemaVersion)) {
            throw failure(WarmupFailure.MANIFEST_SCHEMA_MISMATCH,
                    manifest.schemaVersion() + " != " + expectedManifestSchemaVersion);
        }
        for (DatasetObjectSnapshot object : manifest.objects()) {
            if (!object.schemaVersion().equals(expectedObjectSchemaVersion)) {
                throw failure(WarmupFailure.MANIFEST_OBJECT_SCHEMA_MISMATCH, object.objectKey());
            }
            if (!object.declaredContentSha256().equals(object.observedContentSha256())) {
                throw failure(WarmupFailure.MANIFEST_OBJECT_INTEGRITY_MISMATCH, object.objectKey());
            }
        }
    }

    private static void validateSeriesIdentity(
            WarmupRequirement requirement,
            WarmupFeatureSeries series,
            DatasetManifestSnapshot manifest) {
        if (!series.requirementId().equals(requirement.requirementId())
                || !series.featureId().equals(requirement.featureId())) {
            throw failure(WarmupFailure.REQUIREMENT_FEATURE_MISMATCH, requirement.requirementId());
        }
        if (!series.featureVersion().equals(requirement.featureVersion())) {
            throw failure(WarmupFailure.REQUIREMENT_VERSION_MISMATCH, requirement.requirementId());
        }
        if (!series.resolution().equals(requirement.resolution())) {
            throw failure(WarmupFailure.REQUIREMENT_RESOLUTION_MISMATCH, requirement.requirementId());
        }
        if (!series.manifestId().equals(manifest.manifestId())
                || !series.datasetHash().equals(manifest.datasetHash())) {
            throw failure(WarmupFailure.REQUIREMENT_MANIFEST_MISMATCH, requirement.requirementId());
        }
    }

    private static WarmupFeatureSeries selectRequiredObservations(
            WarmupRequest request,
            WarmupRequirement requirement,
            WarmupFeatureSeries series) {
        Set<String> requestedInstruments = requirement.instruments();
        Map<String, List<FeatureObservation>> byInstrument = new HashMap<>();
        Set<ObservationKey> seen = new HashSet<>();

        for (FeatureObservation observation : series.observations()) {
            if (!requestedInstruments.contains(observation.instrument())) {
                continue;
            }
            if (!observation.observedAt().isBefore(request.startupTime())) {
                throw failure(WarmupFailure.REQUIREMENT_OBSERVATION_OUT_OF_RANGE,
                        requirement.requirementId() + "/" + observation.instrument());
            }
            if (!seen.add(new ObservationKey(observation.instrument(), observation.observedAt()))) {
                throw failure(WarmupFailure.REQUIREMENT_OBSERVATION_DUPLICATE,
                        requirement.requirementId() + "/" + observation.instrument());
            }
            byInstrument.computeIfAbsent(observation.instrument(), ignored -> new ArrayList<>()).add(observation);
        }

        List<FeatureObservation> selected = new ArrayList<>();
        for (String instrument : requestedInstruments) {
            List<FeatureObservation> observations = byInstrument.getOrDefault(instrument, List.of())
                    .stream()
                    .sorted(BY_TIME)
                    .toList();
            if (observations.size() < requirement.requiredObservations()) {
                throw failure(WarmupFailure.REQUIREMENT_COVERAGE_INSUFFICIENT,
                        requirement.requirementId() + "/" + instrument + ": "
                                + observations.size() + " < " + requirement.requiredObservations());
            }
            selected.addAll(observations.subList(
                    observations.size() - requirement.requiredObservations(), observations.size()));
        }
        selected.sort(Comparator.comparing(FeatureObservation::instrument).thenComparing(BY_TIME));

        return new WarmupFeatureSeries(
                series.requirementId(), series.featureId(), series.featureVersion(), series.resolution(),
                series.manifestId(), series.datasetHash(), selected);
    }

    private static WarmupException failure(WarmupFailure failure, String detail) {
        return new WarmupException(failure, detail);
    }

    private record ObservationKey(String instrument, Instant observedAt) {
    }
}
