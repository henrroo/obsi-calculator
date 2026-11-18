package com.kraken.obsi.index;

import com.kraken.obsi.MethodologyParameters;
import com.kraken.obsi.consolidation.ConsolidatedBook;
import com.kraken.obsi.curve.PriceVolumeCurves;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Utilized depth and index value, methodology section 5. Pure and stateless.
 *
 * <p>Everything up to the weights is exact. The weights λe<sup>−λv</sup> are transcendental and
 * are computed to {@link #PRECISION} significant digits, far beyond the two published decimals.
 */
public final class IndexCalculator {

    /** Working precision for λ, the exponential weights and the final division. */
    static final MathContext PRECISION = new MathContext(40, RoundingMode.HALF_EVEN);

    private final MethodologyParameters parameters;

    /** Calculator with the fixed section 7 parameters. */
    public IndexCalculator() {
        this(MethodologyParameters.DEFAULT);
    }

    public IndexCalculator(MethodologyParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    /**
     * Eqs. 2 and 3 on a consolidated, uncrossed book.
     *
     * @throws UtilizedDepthException if the set in Eq. 2 is empty
     */
    public IndexValue calculate(ConsolidatedBook book) throws UtilizedDepthException {
        PriceVolumeCurves curves = new PriceVolumeCurves(book, parameters.spacing());
        BigDecimal utilizedDepth = utilizedDepth(curves);
        BigDecimal value = weightedMidPrice(curves, utilizedDepth);
        return new IndexValue(
                value,
                value.setScale(parameters.publishedScale(), parameters.publishedRounding()),
                utilizedDepth);
    }

    /**
     * Eq. 2: the largest sampled volume v_i with midSV(v_i) ≤ D and midSV(v_i+1) > D, considering
     * only candidates where both curves are defined at v_i and v_i+1.
     *
     * @throws UtilizedDepthException if no candidate satisfies the condition
     */
    public BigDecimal utilizedDepth(PriceVolumeCurves curves) throws UtilizedDepthException {
        long samples = curves.definedSampleCount();
        if (samples < 2) {
            throw new UtilizedDepthException(
                    "insufficient depth: both curves are defined at " + samples
                            + " sampled volume(s), at least 2 consecutive samples are needed");
        }

        BigDecimal cutoff = parameters.midSpreadCutoff();
        long utilized = 0;
        boolean withinAtCurrent = isWithin(curves, 1, cutoff);
        for (long i = 1; i < samples; i++) {
            boolean withinAtNext = isWithin(curves, i + 1, cutoff);
            if (withinAtCurrent && !withinAtNext) {
                utilized = i; // ascending scan: the last match is the maximum
            }
            withinAtCurrent = withinAtNext;
        }

        if (utilized == 0) {
            throw new UtilizedDepthException("no mid-spread cutoff crossing before either curve ends");
        }
        return curves.sampledVolume(utilized);
    }

    /**
     * Eq. 3: OBSI = Σ midPV(v) · λe<sup>−λv</sup> / NF over v = s, 2s, …, v_T, with
     * λ = 1 / (0.3 · v_T) and NF = Σ λe<sup>−λv</sup>.
     *
     * <p>The sum is evaluated as midPV(s) + Σ (midPV(v) − midPV(s)) · λe<sup>−λv</sup> / NF. This is
     * the same quantity, because the normalised weights sum to one, but it keeps the result exact
     * whenever the mid-price curve is flat: there the correction term is exactly zero, so a value
     * such as 100.125 cannot drift to 100.1249… through weight rounding and be published as 100.12.
     */
    BigDecimal weightedMidPrice(PriceVolumeCurves curves, BigDecimal utilizedDepth) {
        BigDecimal lambda = lambda(utilizedDepth);
        long samples = utilizedDepth.divide(curves.spacing(), 0, RoundingMode.UNNECESSARY).longValueExact();

        BigDecimal base = midPVAt(curves, 1);
        BigDecimal normalizationFactor = BigDecimal.ZERO;
        BigDecimal weightedDeviation = BigDecimal.ZERO;
        for (long i = 1; i <= samples; i++) {
            BigDecimal weight = weight(lambda, curves.sampledVolume(i));
            normalizationFactor = normalizationFactor.add(weight, PRECISION);
            weightedDeviation = weightedDeviation.add(midPVAt(curves, i).subtract(base).multiply(weight, PRECISION), PRECISION);
        }
        return base.add(weightedDeviation.divide(normalizationFactor, PRECISION));
    }

    /** λ = 1 / (0.3 · v_T). */
    BigDecimal lambda(BigDecimal utilizedDepth) {
        return BigDecimal.ONE.divide(parameters.lambdaDepthFactor().multiply(utilizedDepth), PRECISION);
    }

    /** λe<sup>−λv</sup>. */
    static BigDecimal weight(BigDecimal lambda, BigDecimal volume) {
        return lambda.multiply(BigDecimalMath.exp(lambda.multiply(volume).negate(), PRECISION), PRECISION);
    }

    private static boolean isWithin(PriceVolumeCurves curves, long i, BigDecimal cutoff) {
        return curves.isWithinCutoff(curves.sampledVolume(i), cutoff)
                .orElseThrow(() -> new IllegalStateException("curves undefined at sample " + i));
    }

    private static BigDecimal midPVAt(PriceVolumeCurves curves, long i) {
        return curves.midPV(curves.sampledVolume(i))
                .orElseThrow(() -> new IllegalStateException("midPV undefined at sample " + i));
    }
}
