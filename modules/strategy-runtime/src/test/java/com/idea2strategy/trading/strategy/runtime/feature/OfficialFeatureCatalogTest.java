package com.idea2strategy.trading.strategy.runtime.feature;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code RSI_14} against D's own worked vectors.
 *
 * <p>The specification in {@code backtest_engine.elements.features} publishes these five vectors as
 * "usable directly as Java test vectors", so they are transcribed rather than recomputed: a vector
 * this side derived from this side's arithmetic would prove only self-consistency, which is exactly
 * the failure D92 exists to catch. The rendered string is asserted, not the numeric value, because it
 * is the rendered form that reaches the step trace and the reproducibility hash.
 */
class OfficialFeatureCatalogTest {

    static Stream<Arguments> dWorkedVectors() {
        return Stream.of(
                Arguments.of("fourteen rises of one", ascending(100, 15), "100.00000000"),
                Arguments.of("fourteen falls of one", descending(114, 15), "0.00000000"),
                Arguments.of("perfectly flat window", flat(100, 15), "50.00000000"),
                Arguments.of("seven +2 then seven -1", sevenUpSevenDown(), "66.66666667"),
                Arguments.of("one +1, one -2, twelve flat", oneUpOneDownThenFlat(), "33.33333333"));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("dWorkedVectors")
    void reproducesDWorkedVectors(String name, List<BigDecimal> closes, String expected) {
        assertEquals(expected, OfficialFeatureCatalog.RSI_14.compute(closes).toPlainString());
    }

    @Test
    void publishesTheDefinitionDDeclares() {
        var rsi = OfficialFeatureCatalog.RSI_14;
        assertAll(
                () -> assertEquals("features:1.0.0", OfficialFeatureCatalog.CATALOG_VERSION),
                () -> assertEquals("RSI_14", rsi.featureId()),
                () -> assertEquals("rsi:1.0.0", rsi.definitionVersion()),
                () -> assertEquals("rsi", rsi.slug()),
                // B's requiredFeature.featureVersion carries no slug, so this half is what the two
                // contracts compare directly.
                () -> assertEquals("1.0.0", rsi.semanticVersion()),
                () -> assertEquals(
                        OfficialFeature.FeatureMethod.SIMPLE_AVERAGE_BOUNDED_WINDOW, rsi.method()),
                () -> assertEquals(OfficialFeature.FeatureDataKind.ADJUSTED_BAR, rsi.dataKind()),
                () -> assertEquals(14, rsi.periods()),
                () -> assertEquals(15, rsi.requiredBars()),
                () -> assertEquals(8, rsi.valueScale()),
                () -> assertTrue(OfficialFeatureCatalog.find("RSI_14").isPresent()),
                () -> assertTrue(OfficialFeatureCatalog.find("WILDERS_RSI_14").isEmpty()));
    }

    /** Warm-up is not a value: a short or long window is refused rather than approximated. */
    @Test
    void refusesAWindowThatIsNotExactlyFifteenBars() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> OfficialFeatureCatalog.RSI_14.compute(ascending(100, 14))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> OfficialFeatureCatalog.RSI_14.compute(ascending(100, 16))));
    }

    /**
     * A zero result renders as {@code 0.00000000}, never {@code -0.00000000}. The two compare equal
     * but render differently, and the rendered form is what the reproducibility hash sees — D hit this
     * as a real defect, so the sign is pinned here too.
     */
    @Test
    void rendersZeroWithoutASign() {
        assertAll(
                () -> assertEquals("0.00000000",
                        OfficialFeatureCatalog.quantize(new BigDecimal("-0.000000001")).toPlainString()),
                () -> assertEquals("0.00000000",
                        OfficialFeatureCatalog.quantize(new BigDecimal("0.000000001")).toPlainString()),
                () -> assertEquals("0.00000000",
                        OfficialFeatureCatalog.RSI_14.compute(descending(114, 15)).toPlainString()));
    }

    /** Exactness matters at the boundary: HALF_EVEN at scale 8, not a truncation. */
    @Test
    void quantizesHalfEvenAtScaleEight() {
        assertAll(
                () -> assertEquals("1.00000002",
                        OfficialFeatureCatalog.quantize(new BigDecimal("1.000000015")).toPlainString()),
                () -> assertEquals("1.00000004",
                        OfficialFeatureCatalog.quantize(new BigDecimal("1.000000045")).toPlainString()));
    }

    // ------------------------------------------------------------------ vectors

    private static List<BigDecimal> ascending(int from, int count) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            closes.add(BigDecimal.valueOf(from + index));
        }
        return closes;
    }

    private static List<BigDecimal> descending(int from, int count) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            closes.add(BigDecimal.valueOf(from - index));
        }
        return closes;
    }

    private static List<BigDecimal> flat(int value, int count) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            closes.add(BigDecimal.valueOf(value));
        }
        return closes;
    }

    /** {@code 100,102,101,103,…,108,107}: seven rises of two, seven falls of one. */
    private static List<BigDecimal> sevenUpSevenDown() {
        List<BigDecimal> closes = new ArrayList<>();
        closes.add(BigDecimal.valueOf(100));
        int current = 100;
        for (int step = 0; step < 7; step++) {
            current += 2;
            closes.add(BigDecimal.valueOf(current));
            current -= 1;
            closes.add(BigDecimal.valueOf(current));
        }
        return closes;
    }

    /** {@code 100, 101, 99, 99, …}: one rise of one, one fall of two, then twelve flat. */
    private static List<BigDecimal> oneUpOneDownThenFlat() {
        List<BigDecimal> closes = new ArrayList<>();
        closes.add(BigDecimal.valueOf(100));
        closes.add(BigDecimal.valueOf(101));
        for (int index = 0; index < 13; index++) {
            closes.add(BigDecimal.valueOf(99));
        }
        return closes;
    }
}
