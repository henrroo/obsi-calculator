package com.kraken.obsi.index;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Transcendental functions on {@link BigDecimal}, which the JDK does not provide. Only what the
 * methodology needs: the exponential in the weights of Eq. 3.
 */
final class BigDecimalMath {

    /** Extra digits carried internally so the result is correct to the requested precision. */
    private static final int GUARD_DIGITS = 10;

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private BigDecimalMath() {
    }

    /**
     * e<sup>x</sup> rounded to {@code mc}.
     *
     * <p>Negative arguments are computed as 1 / e<sup>|x|</sup> so the Taylor series never
     * alternates in sign (no cancellation). The argument is halved until it is below 1, the series
     * summed, and the result squared back; each squaring doubles the relative error, so one guard
     * digit is added per halving.
     */
    static BigDecimal exp(BigDecimal x, MathContext mc) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(mc, "mc");
        if (mc.getPrecision() == 0) {
            throw new IllegalArgumentException("exp needs a finite precision");
        }
        if (x.signum() == 0) {
            return BigDecimal.ONE.round(mc);
        }
        if (x.signum() < 0) {
            MathContext work = new MathContext(mc.getPrecision() + GUARD_DIGITS, RoundingMode.HALF_EVEN);
            return BigDecimal.ONE.divide(exp(x.negate(), work), mc);
        }

        int halvings = 0;
        BigDecimal reduced = x;
        while (reduced.compareTo(BigDecimal.ONE) >= 0) {
            reduced = reduced.multiply(HALF); // exact in decimal
            halvings++;
        }
        MathContext work = new MathContext(mc.getPrecision() + GUARD_DIGITS + halvings, RoundingMode.HALF_EVEN);

        // Taylor series: sum of r^n / n!. With 0 < r < 1 every term is smaller than the last, and
        // the sum is at least 1, so stopping once a term falls below 10^-precision is enough.
        BigDecimal threshold = BigDecimal.ONE.movePointLeft(work.getPrecision());
        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        for (int n = 1; term.compareTo(threshold) > 0; n++) {
            term = term.multiply(reduced, work).divide(BigDecimal.valueOf(n), work);
            sum = sum.add(term, work);
        }

        for (int i = 0; i < halvings; i++) {
            sum = sum.multiply(sum, work);
        }
        return sum.round(mc);
    }
}
