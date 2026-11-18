package com.kraken.obsi.screening;

import com.kraken.obsi.model.Snapshot;

import java.math.BigDecimal;
import java.util.Objects;

/** The baseline methodology's single PED limit, applied to every snapshot. */
public record StaticPedLimit(BigDecimal limit) implements PedLimitPolicy {

    public StaticPedLimit {
        Objects.requireNonNull(limit, "limit");
        if (limit.signum() <= 0) {
            throw new IllegalArgumentException("PED limit must be strictly positive: " + limit);
        }
    }

    @Override
    public BigDecimal limitFor(Snapshot snapshot) {
        return limit;
    }
}
