package com.kraken.obsi.index;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Result of Eqs. 2 and 3.
 *
 * @param value          OBSI_T before rounding
 * @param publishedValue OBSI_T rounded for publication (two decimals, {@code HALF_UP})
 * @param utilizedDepth  v_T, in XYZ
 */
public record IndexValue(BigDecimal value, BigDecimal publishedValue, BigDecimal utilizedDepth) {

    public IndexValue {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(publishedValue, "publishedValue");
        Objects.requireNonNull(utilizedDepth, "utilizedDepth");
    }
}
