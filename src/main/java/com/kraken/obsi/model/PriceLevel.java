package com.kraken.obsi.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One order book level as supplied: both components are numeric, but not yet screened for sign.
 *
 * @param price USD per XYZ
 * @param size  quantity in XYZ
 */
public record PriceLevel(BigDecimal price, BigDecimal size) {

    public PriceLevel {
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(size, "size");
    }

    /** Entry screen of methodology section 2: both price and size must be strictly positive. */
    public boolean isStrictlyPositive() {
        return price.signum() > 0 && size.signum() > 0;
    }
}
