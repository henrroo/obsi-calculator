package com.kraken.obsi.curve;

import com.kraken.obsi.consolidation.ConsolidatedBook;
import com.kraken.obsi.model.PriceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceVolumeCurvesTest {

    private static final BigDecimal D = new BigDecimal("0.005");

    /** Alpha + Beta of the sample input after consolidation (Gamma and Delta are screened out). */
    private static final ConsolidatedBook SAMPLE = new ConsolidatedBook(
            List.of(level("100.2", "0.8"), level("100.0", "1.2"), level("99.8", "2.3"), level("99.1", "2.0"),
                    level("99.0", "2.0"), level("98.1", "2.0"), level("98.0", "2.0")),
            List.of(level("100.3", "0.8"), level("100.4", "1.1"), level("100.6", "2.4"), level("101.1", "2.0"),
                    level("101.2", "2.0"), level("102.0", "2.0"), level("102.1", "2.0")));

    private static PriceLevel level(String price, String size) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static PriceVolumeCurves curves(ConsolidatedBook book) {
        return new PriceVolumeCurves(book, BigDecimal.ONE);
    }

    /** Asserts a numeric value regardless of scale. */
    private static void assertValue(String expected, Optional<BigDecimal> actual) {
        assertTrue(actual.isPresent(), "expected " + expected + " but the curve is undefined");
        assertEquals(0, d(expected).compareTo(actual.get()), () -> "expected " + expected + " but was " + actual.get());
    }

    @Nested
    @DisplayName("Eq. 1a / 1b: unspaced curves")
    class Unspaced {

        // Cumulative ask sizes: 0.8, 1.9, 4.3, 6.3, 8.3, 10.3, 12.3
        @ParameterizedTest(name = "askPV^({0}) = {1}")
        @CsvSource({
                "0.0001, 100.3",
                "0.8,    100.3",  // cum(1) = 0.8 >= v: still level 1
                "0.8001, 100.4",  // just past level 1
                "1.9,    100.4",
                "1.95,   100.6",
                "4.3,    100.6",
                "12.3,   102.1"   // the very last unit of depth
        })
        void askCurveReturnsTheLevelWhereCumulativeSizeFirstReachesV(String v, String price) {
            assertValue(price, curves(SAMPLE).unspacedAskPV(d(v)));
        }

        // Cumulative bid sizes: 0.8, 2.0, 4.3, 6.3, 8.3, 10.3, 12.3
        @ParameterizedTest(name = "bidPV^({0}) = {1}")
        @CsvSource({
                "0.5,  100.2",
                "0.8,  100.2",
                "1,    100.0",
                "2.0,  100.0",
                "2.01, 99.8",
                "12.3, 98.0"
        })
        void bidCurveReturnsTheLevelWhereCumulativeSizeFirstReachesV(String v, String price) {
            assertValue(price, curves(SAMPLE).unspacedBidPV(d(v)));
        }

        @Test
        void undefinedBeyondTheAvailableDepth() {
            assertTrue(curves(SAMPLE).unspacedAskPV(d("12.3000001")).isEmpty());
            assertTrue(curves(SAMPLE).unspacedBidPV(d("13")).isEmpty());
        }

        @Test
        void undefinedAtZeroAndNegativeVolume() {
            // Eq. 1a needs an empty sum (0) to be < v.
            assertTrue(curves(SAMPLE).unspacedAskPV(BigDecimal.ZERO).isEmpty());
            assertTrue(curves(SAMPLE).unspacedBidPV(d("-1")).isEmpty());
        }
    }

    @Nested
    @DisplayName("Eq. 1c / 1d: spacing")
    class Spacing {

        @Test
        void volumeIsRoundedUpToTheNextMultipleOfTheSpacing() {
            PriceVolumeCurves c = curves(SAMPLE);

            // s = 1: v = 0.5 reads the curve at 1, v = 1.5 at 2.
            assertValue("100.4", c.askPV(d("0.5")));   // askPV^(1); askPV^(0.5) would be 100.3
            assertValue("100.0", c.bidPV(d("0.5")));   // bidPV^(1); bidPV^(0.5) would be 100.2
            assertValue("100.6", c.askPV(d("1.5")));   // askPV^(2); askPV^(1.5) would be 100.4
            assertValue("100.0", c.bidPV(d("1.5")));   // bidPV^(2)
            assertValue("100.4", c.askPV(BigDecimal.ONE)); // integer volumes are unchanged
        }

        @Test
        void spacedCurveIsUndefinedWhenTheRoundedVolumeExceedsTheDepth() {
            // 12.3 XYZ on each side: v = 12.1 rounds up to 13, past the depth.
            assertTrue(curves(SAMPLE).askPV(d("12.1")).isEmpty());
            assertValue("102.1", curves(SAMPLE).unspacedAskPV(d("12.1")));
        }

        @Test
        void honoursANonUnitSpacing() {
            PriceVolumeCurves halfSpaced = new PriceVolumeCurves(SAMPLE, d("0.5"));

            assertValue("100.3", halfSpaced.askPV(d("0.3")));  // s = 0.5: read at 0.5, cum(1) = 0.8
            assertValue("100.4", halfSpaced.askPV(d("0.9")));  // read at 1.0, past cum(1) = 0.8
            assertEquals(24, halfSpaced.definedSampleCount()); // floor(12.3 / 0.5)
            assertEquals(0, d("1.5").compareTo(halfSpaced.sampledVolume(3)));
        }

        @Test
        void rejectsNonPositiveSpacing() {
            assertThrows(IllegalArgumentException.class, () -> new PriceVolumeCurves(SAMPLE, BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> new PriceVolumeCurves(SAMPLE, d("-1")));
        }
    }

    @Nested
    @DisplayName("Eq. 1e / 1f: mid-price and mid-spread curves on the sample")
    class MidCurves {

        /** Hand-derived from the consolidated sample book. */
        @ParameterizedTest(name = "v = {0}: ask {1}, bid {2}, mid {3}, within D: {4}")
        @CsvSource({
                "1, 100.4, 100.0, 100.2,  true",   // midSV = 0.2 / 100.2  = 0.001996
                "2, 100.6, 100.0, 100.3,  true",   // midSV = 0.3 / 100.3  = 0.002991
                "3, 100.6, 99.8,  100.2,  true",   // midSV = 0.4 / 100.2  = 0.003992
                "4, 100.6, 99.8,  100.2,  true",
                "5, 101.1, 99.1,  100.1,  false",  // midSV = 1.0 / 100.1  = 0.009990
                "12, 102.1, 98.0, 100.05, false"
        })
        void matchHandDerivedValues(long v, String ask, String bid, String mid, boolean within) {
            PriceVolumeCurves c = curves(SAMPLE);
            BigDecimal volume = BigDecimal.valueOf(v);

            assertValue(ask, c.askPV(volume));
            assertValue(bid, c.bidPV(volume));
            assertValue(mid, c.midPV(volume));
            assertEquals(Optional.of(within), c.isWithinCutoff(volume, D));
        }

        @Test
        void midSpreadIsAskOverMidMinusOne() {
            BigDecimal midSV = curves(SAMPLE).midSV(BigDecimal.ONE, MathContext.DECIMAL128).orElseThrow();

            // 100.4 / 100.2 - 1 = 0.001996007984031936127744510978043912...
            assertEquals(0, d("0.001996007984031936127744510978044").compareTo(midSV.round(new MathContext(31))));
        }

        @Test
        void midIsExact() {
            ConsolidatedBook book = new ConsolidatedBook(List.of(level("0.1", "1")), List.of(level("0.2", "1")));

            assertEquals(0, d("0.15").compareTo(curves(book).midPV(BigDecimal.ONE).orElseThrow()));
        }

        @Test
        void midCurvesAreUndefinedWhereEitherSideRunsOut() {
            ConsolidatedBook lopsided = new ConsolidatedBook(
                    List.of(level("99", "1")),
                    List.of(level("101", "5")));
            PriceVolumeCurves c = curves(lopsided);

            assertValue("101", c.askPV(d("2")));
            assertTrue(c.bidPV(d("2")).isEmpty());
            assertTrue(c.midPV(d("2")).isEmpty());
            assertTrue(c.midSV(d("2"), MathContext.DECIMAL128).isEmpty());
            assertTrue(c.isWithinCutoff(d("2"), D).isEmpty());
        }
    }

    @Nested
    @DisplayName("cutoff comparison")
    class Cutoff {

        @Test
        void midSpreadExactlyAtTheCutoffIsWithin() {
            // ask 201, bid 199: mid 200, midSV = 201 / 200 - 1 = 0.005 exactly.
            ConsolidatedBook atCutoff = new ConsolidatedBook(List.of(level("199", "1")), List.of(level("201", "1")));

            assertEquals(Optional.of(true), curves(atCutoff).isWithinCutoff(BigDecimal.ONE, D));
            assertEquals(0, D.compareTo(curves(atCutoff).midSV(BigDecimal.ONE, MathContext.DECIMAL128).orElseThrow()));
        }

        @Test
        void midSpreadJustAboveTheCutoffIsNotWithin() {
            ConsolidatedBook above = new ConsolidatedBook(
                    List.of(level("199", "1")), List.of(level("201.0000000000000000000001", "1")));

            assertEquals(Optional.of(false), curves(above).isWithinCutoff(BigDecimal.ONE, D));
        }
    }

    @Nested
    @DisplayName("defined sample count")
    class DefinedSamples {

        @Test
        void isLimitedByTheShallowerSideAndFloored() {
            ConsolidatedBook book = new ConsolidatedBook(
                    List.of(level("99", "2.5"), level("98", "1.4")),  // 3.9
                    List.of(level("101", "10")));                     // 10

            assertEquals(3, curves(book).definedSampleCount());
        }

        @Test
        void isZeroWhenLessThanOneUnitOfDepth() {
            ConsolidatedBook book = new ConsolidatedBook(List.of(level("99", "0.99")), List.of(level("101", "5")));

            assertEquals(0, curves(book).definedSampleCount());
        }

        @Test
        void sampleBookHasTwelveSamples() {
            assertEquals(12, curves(SAMPLE).definedSampleCount()); // floor(12.3)
        }
    }
}
