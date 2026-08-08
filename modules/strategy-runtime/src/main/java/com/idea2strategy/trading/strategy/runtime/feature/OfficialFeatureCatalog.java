package com.idea2strategy.trading.strategy.runtime.feature;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The features this build implements, pinned to the same specification D's backtest runtime uses.
 *
 * <p>D92 requires the Python backtest runtime and this Java trading runtime to reach the same
 * decision from the same inputs. A feature is therefore reproduced here clause by clause from the
 * normative specification in {@code backtest_engine.elements.features}, including the arithmetic:
 * exact decimal arithmetic at 34 significant digits with HALF_EVEN — IEEE 754 {@code decimal128},
 * the precision D's {@code localcontext} sets — and then a single final quantization of the result to
 * 8 fractional digits, also HALF_EVEN. Binary floating point is not permitted anywhere in a feature:
 * {@code double} does not round-trip the 8-decimal contract value.
 *
 * <p>A build that implements a feature differently in any clause implements a different feature and
 * must publish a different {@code definitionVersion}, never the same one.
 */
public final class OfficialFeatureCatalog {

    /** Version of the feature <em>set</em>; each feature also carries its own version. */
    public static final String CATALOG_VERSION = "features:1.0.0";

    /** The scale every catalog value is quantized to before it leaves a computation. */
    public static final int VALUE_SCALE = 8;

    /** IEEE 754 {@code decimal128} significand width. */
    public static final MathContext WORKING_PRECISION = new MathContext(34, RoundingMode.HALF_EVEN);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * A perfectly flat window has no relative strength to measure: {@code U/V} is undefined when both
     * are zero. 50 is a pinned convention — neither overbought nor oversold — not a mathematical
     * result, and it has to be reproduced verbatim on both sides.
     */
    private static final BigDecimal FLAT_WINDOW_RSI = BigDecimal.valueOf(50);

    private static final int RSI_14_PERIODS = 14;

    /**
     * {@code RSI_14} under {@code rsi:1.0.0}: the simple-average ("Cutler's") RSI over a bounded
     * 15-bar window, <em>not</em> Wilder's exponentially smoothed RSI.
     *
     * <p>With {@code C[0..14]} the closes of the window bars, oldest first:
     * <pre>
     *   D[i] = C[i] - C[i-1]                    for i = 1..14
     *   U    = (sum of max(D[i], 0)) / 14
     *   V    = (sum of max(-D[i], 0)) / 14
     *
     *   V == 0 and U == 0 -&gt; 50     (flat window, pinned convention)
     *   V == 0            -&gt; 100    (only gains)
     *   otherwise         -&gt; 100 - 100 / (1 + U / V)
     * </pre>
     */
    public static final OfficialFeature RSI_14 = new OfficialFeature(
            "RSI_14",
            "rsi:1.0.0",
            OfficialFeature.FeatureMethod.SIMPLE_AVERAGE_BOUNDED_WINDOW,
            OfficialFeature.FeatureDataKind.ADJUSTED_BAR,
            RSI_14_PERIODS,
            VALUE_SCALE,
            OfficialFeatureCatalog::computeRsi14);

    private static final Map<String, OfficialFeature> REGISTRY = Map.of(RSI_14.featureId(), RSI_14);

    private OfficialFeatureCatalog() {}

    /** The definition this build implements for {@code featureId}, or empty if it implements none. */
    public static Optional<OfficialFeature> find(String featureId) {
        return Optional.ofNullable(REGISTRY.get(featureId));
    }

    /** Every feature this build implements, by id. */
    public static Map<String, OfficialFeature> features() {
        return REGISTRY;
    }

    private static BigDecimal computeRsi14(List<BigDecimal> closes) {
        BigDecimal gainTotal = BigDecimal.ZERO;
        BigDecimal lossTotal = BigDecimal.ZERO;
        for (int index = 1; index < closes.size(); index++) {
            BigDecimal change = closes.get(index).subtract(closes.get(index - 1), WORKING_PRECISION);
            int sign = change.signum();
            if (sign > 0) {
                gainTotal = gainTotal.add(change, WORKING_PRECISION);
            } else if (sign < 0) {
                lossTotal = lossTotal.subtract(change, WORKING_PRECISION);
            }
        }
        BigDecimal periods = BigDecimal.valueOf(RSI_14_PERIODS);
        BigDecimal averageGain = gainTotal.divide(periods, WORKING_PRECISION);
        BigDecimal averageLoss = lossTotal.divide(periods, WORKING_PRECISION);

        BigDecimal value;
        if (averageLoss.signum() == 0) {
            value = averageGain.signum() == 0 ? FLAT_WINDOW_RSI : HUNDRED;
        } else {
            BigDecimal relativeStrength = averageGain.divide(averageLoss, WORKING_PRECISION);
            value = HUNDRED.subtract(
                    HUNDRED.divide(ONE.add(relativeStrength, WORKING_PRECISION), WORKING_PRECISION),
                    WORKING_PRECISION);
        }
        return quantize(value);
    }

    /**
     * The single final quantization every catalog value passes through.
     *
     * <p>The absolute value is taken when the result is zero on purpose: {@code -0E-8} and
     * {@code 0E-8} compare equal but render differently, and the rendered form reaches the step trace
     * and the reproducibility hash — D hit exactly this and fixed it the same way, so one number
     * cannot produce two hashes.
     */
    public static BigDecimal quantize(BigDecimal value) {
        BigDecimal quantized = value.setScale(VALUE_SCALE, RoundingMode.HALF_EVEN);
        return quantized.signum() == 0 ? quantized.abs() : quantized;
    }
}
