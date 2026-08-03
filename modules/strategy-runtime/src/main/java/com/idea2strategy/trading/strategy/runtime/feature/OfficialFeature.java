package com.idea2strategy.trading.strategy.runtime.feature;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One pinned, independently versioned feature computation, as the official catalog defines it.
 *
 * <p>The definitions are shared with D's Python backtest runtime, where each is specified as an exact
 * arithmetic procedure rather than as "an RSI". D92 requires the two runtimes to agree on the same
 * inputs, so an implementation that differs in any clause of the specification is a different feature
 * and needs a different {@link #definitionVersion()}.
 *
 * @see OfficialFeatureCatalog
 */
public record OfficialFeature(
        String featureId,
        String definitionVersion,
        FeatureMethod method,
        FeatureDataKind dataKind,
        int periods,
        int valueScale,
        WindowComputation computation) {

    public OfficialFeature {
        featureId = requireText(featureId, "featureId");
        definitionVersion = requireText(definitionVersion, "definitionVersion");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(dataKind, "dataKind");
        if (periods <= 0) {
            throw new IllegalArgumentException("periods must be positive");
        }
        if (valueScale < 0) {
            throw new IllegalArgumentException("valueScale must not be negative");
        }
        Objects.requireNonNull(computation, "computation");
        if (definitionVersion.indexOf(':') < 0) {
            throw new IllegalArgumentException("definitionVersion must be <slug>:<major.minor.patch>");
        }
    }

    /** Completed bars needed before the feature has any value at all. */
    public int requiredBars() {
        return periods + 1;
    }

    /** The {@code rsi} of {@code rsi:1.0.0}. */
    public String slug() {
        return definitionVersion.substring(0, definitionVersion.indexOf(':'));
    }

    /**
     * The {@code 1.0.0} of {@code rsi:1.0.0}.
     *
     * <p>B's {@code requiredFeature.featureVersion} is an exact major.minor.patch string with no
     * slug, so this is the half of the version the two contracts compare directly.
     */
    public String semanticVersion() {
        return definitionVersion.substring(definitionVersion.indexOf(':') + 1);
    }

    /**
     * Computes the feature over exactly {@link #requiredBars()} closes, oldest first.
     *
     * <p>The window is closes rather than bars because every catalog definition so far reads only the
     * close. A definition needing more would carry its own input type and a new version.
     */
    public BigDecimal compute(List<BigDecimal> closesOldestFirst) {
        Objects.requireNonNull(closesOldestFirst, "closesOldestFirst");
        if (closesOldestFirst.size() != requiredBars()) {
            throw new IllegalArgumentException(
                    featureId + " requires exactly " + requiredBars() + " bars, got "
                            + closesOldestFirst.size());
        }
        return computation.compute(List.copyOf(closesOldestFirst));
    }

    /** How a definition turns its window into a value. */
    public enum FeatureMethod {
        /**
         * A simple average over a fixed window. The value is a pure function of the last
         * {@code periods + 1} bars, so a backtest seeded at a quarter boundary and a live runtime
         * seeded at process start agree by construction — which an unbounded recursion such as
         * Wilder's smoothing could never do.
         */
        SIMPLE_AVERAGE_BOUNDED_WINDOW
    }

    /** Which series a definition reads. */
    public enum FeatureDataKind {
        /** Corporate-action-adjusted bars. Raw bars are not substitutable. */
        ADJUSTED_BAR
    }

    @FunctionalInterface
    public interface WindowComputation {
        BigDecimal compute(List<BigDecimal> closesOldestFirst);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
