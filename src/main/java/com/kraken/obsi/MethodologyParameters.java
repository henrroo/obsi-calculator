package com.kraken.obsi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Fixed parameters of the OBSI methodology (section 7).
 *
 * <p>Kept as a record rather than scattered constants so every stage reads them from one place and
 * a variant methodology (e.g. a different PED rule) can be expressed as a different instance.
 *
 * @param pedLimit          orderbook percentage deviation limit, as a fraction (5% = 0.05)
 * @param midSpreadCutoff   mid-spread cutoff D, as a fraction (0.5% = 0.005)
 * @param spacing           price-volume curve spacing s, in XYZ
 * @param lambdaDepthFactor the 0.3 in the exponential weighting parameter λ = 1 / (0.3 · v_T)
 * @param publishedScale    decimal places of the published value
 * @param publishedRounding rounding mode of the published value
 */
public record MethodologyParameters(
        BigDecimal pedLimit,
        BigDecimal midSpreadCutoff,
        BigDecimal spacing,
        BigDecimal lambdaDepthFactor,
        int publishedScale,
        RoundingMode publishedRounding) {

    /** The parameters fixed by section 7 of the methodology for this exercise. */
    public static final MethodologyParameters DEFAULT = new MethodologyParameters(
            new BigDecimal("0.05"),
            new BigDecimal("0.005"),
            BigDecimal.ONE,
            new BigDecimal("0.3"),
            2,
            RoundingMode.HALF_UP);

    public MethodologyParameters {
        requirePositive(pedLimit, "pedLimit");
        requirePositive(midSpreadCutoff, "midSpreadCutoff");
        requirePositive(spacing, "spacing");
        requirePositive(lambdaDepthFactor, "lambdaDepthFactor");
        Objects.requireNonNull(publishedRounding, "publishedRounding");
        if (publishedScale < 0) {
            throw new IllegalArgumentException("publishedScale must not be negative: " + publishedScale);
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be strictly positive: " + value);
        }
    }
}
