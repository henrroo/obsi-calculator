package com.kraken.obsi.curve;

import com.kraken.obsi.consolidation.ConsolidatedBook;
import com.kraken.obsi.model.PriceLevel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Price-volume curves of a consolidated, uncrossed book (methodology section 4). Immutable.
 *
 * <p>A curve is defined at volume {@code v} only while the book has enough depth to fill it, that
 * is for {@code 0 < v <= total size} of that side (after spacing). Outside that range every curve
 * returns {@link Optional#empty()}.
 *
 * <p>Every value is exact except {@link #midSV}, which divides and therefore needs a
 * {@link MathContext}. Decisions against the cutoff go through {@link #isWithinCutoff}, which is
 * exact.
 */
public final class PriceVolumeCurves {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final Side asks;
    private final Side bids;
    private final BigDecimal spacing;

    /**
     * @param book    consolidated book with bids ordered best (highest) first and asks best
     *                (lowest) first, as produced by {@code BookConsolidator}
     * @param spacing curve spacing s, strictly positive
     */
    public PriceVolumeCurves(ConsolidatedBook book, BigDecimal spacing) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(spacing, "spacing");
        if (spacing.signum() <= 0) {
            throw new IllegalArgumentException("spacing must be strictly positive: " + spacing);
        }
        this.asks = new Side(book.asks());
        this.bids = new Side(book.bids());
        this.spacing = spacing;
    }

    /** Eq. 1a: price of the ask level at which cumulative ask size first reaches {@code v}. */
    public Optional<BigDecimal> unspacedAskPV(BigDecimal v) {
        return asks.priceAt(v);
    }

    /** Eq. 1b: price of the bid level at which cumulative bid size first reaches {@code v}. */
    public Optional<BigDecimal> unspacedBidPV(BigDecimal v) {
        return bids.priceAt(v);
    }

    /** Eq. 1c: askPV(v) = askPV̂(s · ⌈v / s⌉). */
    public Optional<BigDecimal> askPV(BigDecimal v) {
        return asks.priceAt(spaced(v));
    }

    /** Eq. 1d: bidPV(v) = bidPV̂(s · ⌈v / s⌉). */
    public Optional<BigDecimal> bidPV(BigDecimal v) {
        return bids.priceAt(spaced(v));
    }

    /** Eq. 1e: midPV(v) = (askPV(v) + bidPV(v)) / 2. Defined only where both curves are. */
    public Optional<BigDecimal> midPV(BigDecimal v) {
        return askPV(v).flatMap(ask -> bidPV(v).map(bid -> ask.add(bid).multiply(HALF)));
    }

    /**
     * Eq. 1f: midSV(v) = askPV(v) / midPV(v) − 1, rounded to {@code mc}. For reporting and
     * diagnostics; use {@link #isWithinCutoff} to compare against D.
     */
    public Optional<BigDecimal> midSV(BigDecimal v, MathContext mc) {
        return askPV(v).flatMap(ask -> midPV(v).map(mid -> ask.divide(mid, mc).subtract(BigDecimal.ONE)));
    }

    /**
     * midSV(v) ≤ D, evaluated exactly as askPV(v) ≤ (1 + D) · midPV(v), which is equivalent because
     * midPV is strictly positive. Empty where the curves are undefined.
     */
    public Optional<Boolean> isWithinCutoff(BigDecimal v, BigDecimal cutoff) {
        BigDecimal factor = BigDecimal.ONE.add(cutoff);
        return askPV(v).flatMap(ask -> midPV(v).map(mid -> ask.compareTo(factor.multiply(mid)) <= 0));
    }

    /**
     * Number of sampled volumes v_i = i · s, i = 1, 2, …, at which both the ask and the bid curve
     * are defined: ⌊min(total ask size, total bid size) / s⌋.
     */
    public long definedSampleCount() {
        return asks.totalSize().min(bids.totalSize()).divide(spacing, 0, RoundingMode.FLOOR).longValueExact();
    }

    /** The i-th sampled volume, v_i = i · s. */
    public BigDecimal sampledVolume(long i) {
        return spacing.multiply(BigDecimal.valueOf(i));
    }

    public BigDecimal spacing() {
        return spacing;
    }

    /** s · ⌈v / s⌉, exact. */
    private BigDecimal spaced(BigDecimal v) {
        return v.divide(spacing, 0, RoundingMode.CEILING).multiply(spacing);
    }

    /** One side of the book as prices with running cumulative sizes, best price first. */
    private static final class Side {

        private final List<BigDecimal> prices = new ArrayList<>();
        private final List<BigDecimal> cumulativeSizes = new ArrayList<>();

        Side(List<PriceLevel> levels) {
            BigDecimal cumulative = BigDecimal.ZERO;
            for (PriceLevel level : levels) {
                cumulative = cumulative.add(level.size());
                prices.add(level.price());
                cumulativeSizes.add(cumulative);
            }
        }

        BigDecimal totalSize() {
            return cumulativeSizes.isEmpty() ? BigDecimal.ZERO : cumulativeSizes.getLast();
        }

        /**
         * The price of level j+1 such that cumSize(j) < v ≤ cumSize(j+1), with cumSize(0) = 0.
         * Because sizes are positive the cumulative sizes are strictly increasing, so this is the
         * first level whose cumulative size reaches v.
         */
        Optional<BigDecimal> priceAt(BigDecimal v) {
            if (v.signum() <= 0) {
                return Optional.empty();
            }
            for (int level = 0; level < cumulativeSizes.size(); level++) {
                if (cumulativeSizes.get(level).compareTo(v) >= 0) {
                    return Optional.of(prices.get(level));
                }
            }
            return Optional.empty();
        }
    }
}
