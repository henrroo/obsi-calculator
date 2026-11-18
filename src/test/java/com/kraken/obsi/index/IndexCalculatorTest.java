package com.kraken.obsi.index;

import com.kraken.obsi.MethodologyParameters;
import com.kraken.obsi.consolidation.ConsolidatedBook;
import com.kraken.obsi.curve.PriceVolumeCurves;
import com.kraken.obsi.model.PriceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCalculatorTest {

    private final IndexCalculator calculator = new IndexCalculator();

    /** Alpha + Beta of the sample input after consolidation (Gamma and Delta are screened out). */
    private static final ConsolidatedBook SAMPLE = new ConsolidatedBook(
            List.of(level("100.2", "0.8"), level("100.0", "1.2"), level("99.8", "2.3"), level("99.1", "2.0"),
                    level("99.0", "2.0"), level("98.1", "2.0"), level("98.0", "2.0")),
            List.of(level("100.3", "0.8"), level("100.4", "1.1"), level("100.6", "2.4"), level("101.1", "2.0"),
                    level("101.2", "2.0"), level("102.0", "2.0"), level("102.1", "2.0")));

    /**
     * OBSI of {@link #SAMPLE} from an independent Python reference implementation written from the
     * methodology text (decimal module, 60 digits, Eq. 3 evaluated literally).
     */
    private static final BigDecimal SAMPLE_REFERENCE_VALUE = new BigDecimal("100.225481279564619347544896058706");

    private static PriceLevel level(String price, String size) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static ConsolidatedBook book(List<PriceLevel> bids, List<PriceLevel> asks) {
        return new ConsolidatedBook(bids, asks);
    }

    /**
     * A book whose mid-price is exactly {@code mid} for the first {@code flatDepth} XYZ (spread 0.01),
     * after which the spread jumps to ±30 so the mid-spread cutoff is crossed at v = flatDepth.
     */
    private static ConsolidatedBook flatBook(String mid, int flatDepth) {
        BigDecimal m = d(mid);
        BigDecimal halfSpread = d("0.005");
        BigDecimal depth = BigDecimal.valueOf(flatDepth);
        return book(
                List.of(new PriceLevel(m.subtract(halfSpread), depth), new PriceLevel(m.subtract(d("30")), d("5"))),
                List.of(new PriceLevel(m.add(halfSpread), depth), new PriceLevel(m.add(d("30")), d("5"))));
    }

    private BigDecimal utilizedDepth(ConsolidatedBook book) throws UtilizedDepthException {
        return calculator.utilizedDepth(new PriceVolumeCurves(book, BigDecimal.ONE));
    }

    private static void assertNumericEquals(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Nested
    @DisplayName("Eq. 2: utilized depth")
    class UtilizedDepth {

        @Test
        void sampleCrossesTheCutoffBetweenFourAndFive() throws Exception {
            // midSV: v=4 -> 0.4/100.2 = 0.0040 <= D, v=5 -> 1.0/100.1 = 0.0100 > D
            assertNumericEquals("4", utilizedDepth(SAMPLE));
        }

        @Test
        void crossingAtTheFirstSampleGivesDepthOne() throws Exception {
            ConsolidatedBook book = book(
                    List.of(level("100", "1"), level("99", "1")),
                    List.of(level("100.2", "1"), level("101", "1")));

            assertNumericEquals("1", utilizedDepth(book));
        }

        @Test
        void midSpreadExactlyAtTheCutoffCountsAsWithin() throws Exception {
            // v=1: ask 201, bid 199 -> midSV = 0.005 exactly (<= D); v=2: midSV = 0.25 (> D).
            ConsolidatedBook book = book(
                    List.of(level("199", "1"), level("150", "5")),
                    List.of(level("201", "1"), level("250", "5")));

            assertNumericEquals("1", utilizedDepth(book));
        }

        @Test
        void nextSampleExactlyAtTheCutoffIsNotACrossing() throws Exception {
            // v=1: 0.0025, v=2: exactly 0.005 (still within), v=3: 0.25 -> crossing is at v=2, not v=1.
            ConsolidatedBook book = book(
                    List.of(level("199.5", "1"), level("199", "1"), level("150", "5")),
                    List.of(level("200.5", "1"), level("201", "1"), level("250", "5")));

            assertNumericEquals("2", utilizedDepth(book));
        }

        @Test
        void fractionalLevelSizesAreSampledAtWholeUnits() throws Exception {
            // Cumulative bids 0.4, 1.5, 2.6; asks 0.7, 1.2, 9.2. Bid side limits the depth to 2 samples
            // plus the wide level -> v=1: 100.0/100.1, v=2: 99.9/100.2, v=3: 90/100.2.
            ConsolidatedBook book = book(
                    List.of(level("100.05", "0.4"), level("100.0", "1.1"), level("99.9", "1.1"), level("90", "5")),
                    List.of(level("100.08", "0.7"), level("100.1", "0.5"), level("100.2", "8")));

            assertNumericEquals("2", utilizedDepth(book));
        }

        @Test
        void failsWithInsufficientDepthForTwoConsecutiveSamples() {
            ConsolidatedBook oneSample = book(List.of(level("100", "1.5")), List.of(level("100.1", "3")));

            var e = assertThrows(UtilizedDepthException.class, () -> utilizedDepth(oneSample));
            assertEquals("insufficient depth: both curves are defined at 1 sampled volume(s), "
                    + "at least 2 consecutive samples are needed", e.getMessage());
        }

        @Test
        void failsWithLessThanOneUnitOfDepth() {
            ConsolidatedBook book = book(List.of(level("100", "0.4")), List.of(level("100.1", "0.9")));

            var e = assertThrows(UtilizedDepthException.class, () -> utilizedDepth(book));
            assertTrue(e.getMessage().startsWith("insufficient depth: both curves are defined at 0 "));
        }

        @Test
        void failsWhenTheSpreadNeverCrossesTheCutoff() {
            ConsolidatedBook tight = book(
                    List.of(level("100.0", "1"), level("99.9", "1"), level("99.8", "1")),
                    List.of(level("100.1", "1"), level("100.2", "1"), level("100.3", "1")));

            var e = assertThrows(UtilizedDepthException.class, () -> utilizedDepth(tight));
            assertEquals("no mid-spread cutoff crossing before either curve ends", e.getMessage());
        }

        @Test
        void failsWhenTheSpreadIsAlreadyBeyondTheCutoffAtTheFirstSample() {
            ConsolidatedBook wide = book(List.of(level("99", "5")), List.of(level("101", "5")));

            var e = assertThrows(UtilizedDepthException.class, () -> utilizedDepth(wide));
            assertEquals("no mid-spread cutoff crossing before either curve ends", e.getMessage());
        }

        @Test
        void aCrossingBeyondTheShallowerSideDoesNotCount() {
            // Asks would cross at v=3, but the bid side ends at 2 XYZ, so v_3 is not evaluable.
            ConsolidatedBook book = book(
                    List.of(level("100", "2")),
                    List.of(level("100.1", "2"), level("110", "10")));

            assertThrows(UtilizedDepthException.class, () -> utilizedDepth(book));
        }
    }

    @Nested
    @DisplayName("Eq. 3: weights")
    class Weights {

        @Test
        void lambdaIsOneOverPointThreeTimesTheUtilizedDepth() {
            assertNumericEquals("0.8333333333333333333333333333333333333333", calculator.lambda(d("4")));
            assertNumericEquals("3.333333333333333333333333333333333333333", calculator.lambda(BigDecimal.ONE));
            assertNumericEquals("0.3333333333333333333333333333333333333333", calculator.lambda(d("10")));
        }

        @ParameterizedTest(name = "lambda e^(-lambda v) at v = {0}, v_T = 4")
        @CsvSource({"1", "2", "3", "4"})
        void weightIsLambdaTimesExpOfMinusLambdaV(long v) {
            double lambda = 1 / (0.3 * 4);
            double expected = lambda * Math.exp(-lambda * v);

            BigDecimal weight = IndexCalculator.weight(calculator.lambda(d("4")), BigDecimal.valueOf(v));

            assertEquals(expected, weight.doubleValue(), expected * 1e-15);
        }

        @Test
        void weightsDecreaseWithVolume() {
            BigDecimal lambda = calculator.lambda(d("10"));
            List<BigDecimal> weights = LongStream.rangeClosed(1, 10)
                    .mapToObj(v -> IndexCalculator.weight(lambda, BigDecimal.valueOf(v)))
                    .toList();

            for (int i = 1; i < weights.size(); i++) {
                assertTrue(weights.get(i).compareTo(weights.get(i - 1)) < 0, "weight " + (i + 1) + " >= weight " + i);
            }
        }
    }

    @Nested
    @DisplayName("Eq. 3: index value")
    class Value {

        @Test
        void sampleMatchesTheIndependentReferenceTo28Decimals() throws Exception {
            IndexValue result = calculator.calculate(SAMPLE);

            BigDecimal error = result.value().subtract(SAMPLE_REFERENCE_VALUE).abs();
            assertTrue(error.compareTo(d("1e-28")) < 0, () -> "value " + result.value() + " off by " + error);
            assertNumericEquals("4", result.utilizedDepth());
            assertEquals(d("100.23"), result.publishedValue());
        }

        @Test
        void sampleMatchesTheClosedFormOfItsWeightedMean() throws Exception {
            // midPV = 100.2, 100.3, 100.2, 100.2 for v = 1..4; lambda cancels in the normalisation.
            double lambda = 1 / 1.2;
            double num = 0;
            double den = 0;
            double[] mid = {100.2, 100.3, 100.2, 100.2};
            for (int v = 1; v <= 4; v++) {
                num += mid[v - 1] * Math.exp(-lambda * v);
                den += Math.exp(-lambda * v);
            }

            assertEquals(num / den, calculator.calculate(SAMPLE).value().doubleValue(), 1e-12);
        }

        @Test
        void twoSampleWeightedMeanMatchesTheClosedForm() throws Exception {
            // v=1: 100.0/100.2 (mid 100.1), v=2: 99.9/100.4 (mid 100.15), v=3: 98/102 crosses -> v_T = 2.
            ConsolidatedBook book = book(
                    List.of(level("100.0", "1"), level("99.9", "1"), level("98", "5")),
                    List.of(level("100.2", "1"), level("100.4", "1"), level("102", "5")));
            double lambda = 1 / 0.6;
            double expected = (100.1 + 100.15 * Math.exp(-lambda)) / (1 + Math.exp(-lambda));

            IndexValue result = calculator.calculate(book);

            assertNumericEquals("2", result.utilizedDepth());
            assertEquals(expected, result.value().doubleValue(), 1e-12);
            assertEquals(d("100.11"), result.publishedValue());
        }

        @Test
        void singleSampleValueIsExactlyTheFirstMidPrice() throws Exception {
            ConsolidatedBook book = book(
                    List.of(level("100", "1"), level("99", "1")),
                    List.of(level("100.2", "1"), level("101", "1")));

            IndexValue result = calculator.calculate(book);

            assertNumericEquals("100.1", result.value());
            assertEquals(d("100.10"), result.publishedValue());
        }

        @Test
        void flatMidPriceCurveIsReproducedExactly() throws Exception {
            IndexValue result = calculator.calculate(flatBook("123.456789", 7));

            assertNumericEquals("7", result.utilizedDepth());
            assertNumericEquals("123.456789", result.value()); // not 123.4567889999…
        }

        @Test
        void valueLiesBetweenTheExtremesOfTheMidPriceCurve() throws Exception {
            BigDecimal value = calculator.calculate(SAMPLE).value();

            assertTrue(value.compareTo(d("100.2")) > 0 && value.compareTo(d("100.3")) < 0, value.toString());
        }
    }

    @Nested
    @DisplayName("publication rounding")
    class Rounding {

        @ParameterizedTest(name = "flat mid {0} publishes as {1}")
        @CsvSource({
                "100.125,    100.13",  // the tie: HALF_UP goes up (HALF_EVEN would give 100.12)
                "100.135,    100.14",
                "100.124999, 100.12",
                "100.115,    100.12",
                "99.995,     100.00",
                "100.1,      100.10"   // always two decimals
        })
        void publishedValueIsRoundedHalfUpToTwoDecimals(String mid, String published) throws Exception {
            IndexValue result = calculator.calculate(flatBook(mid, 3));

            assertNumericEquals(mid, result.value());
            assertEquals(d(published), result.publishedValue());
            assertEquals(2, result.publishedValue().scale());
        }

        @Test
        void roundingFollowsTheConfiguredParameters() throws Exception {
            MethodologyParameters halfEven = new MethodologyParameters(
                    d("0.05"), d("0.005"), BigDecimal.ONE, d("0.3"), 2, RoundingMode.HALF_EVEN);

            assertEquals(d("100.12"), new IndexCalculator(halfEven).calculate(flatBook("100.125", 3)).publishedValue());
        }

        @Test
        void unroundedValueIsKeptAlongsideThePublishedOne() throws Exception {
            IndexValue result = calculator.calculate(SAMPLE);

            assertTrue(result.value().scale() > 2);
            assertEquals(result.value().setScale(2, RoundingMode.HALF_UP), result.publishedValue());
        }
    }
}
