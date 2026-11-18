package com.kraken.obsi.screening;

import com.kraken.obsi.MethodologyParameters;
import com.kraken.obsi.model.Book;
import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.PriceLevel;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.model.UnreadableBook;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Constituent order book screens, methodology section 2. Pure and stateless: the snapshot is not
 * modified and the same input always yields the same result.
 *
 * <ol>
 *   <li>Entry screen: discard every price/size entry that is not strictly positive.</li>
 *   <li>Structural screen: exclude a book that is unreadable, has an empty side after the entry
 *       screen, or whose best bid is greater than or equal to its best ask.</li>
 *   <li>Deviation screen: exclude a structurally valid book whose top-of-book mid-price deviates
 *       from the median mid-price of all structurally valid books by more than 5%. Exactly 5%
 *       passes. The median is computed once; it is not recomputed after exclusions.</li>
 * </ol>
 *
 * All arithmetic is exact {@link BigDecimal} arithmetic: halving is done by multiplying by 0.5
 * and the deviation check is evaluated without division, so no rounding is ever applied.
 */
public final class OrderBookScreener {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** Orderbook percentage deviation limit, as a fraction (5% = 0.05). */
    private final BigDecimal pedLimit;

    /** Screener with the fixed section 7 parameters. */
    public OrderBookScreener() {
        this(MethodologyParameters.DEFAULT);
    }

    public OrderBookScreener(MethodologyParameters parameters) {
        this(parameters.pedLimit());
    }

    /** @param pedLimit PED limit as a fraction, e.g. the one a {@link PedLimitPolicy} chose for a snapshot */
    public OrderBookScreener(BigDecimal pedLimit) {
        if (pedLimit.signum() <= 0) {
            throw new IllegalArgumentException("PED limit must be strictly positive: " + pedLimit);
        }
        this.pedLimit = pedLimit;
    }

    /** A book after the entry and structural screens. */
    private sealed interface Screened {

        record Valid(ExchangeBook book, BigDecimal midPrice) implements Screened {
        }

        record Invalid(String exchange) implements Screened {
        }
    }

    public ScreeningResult screen(Snapshot snapshot) {
        return screen(snapshot.books());
    }

    public ScreeningResult screen(List<? extends Book> books) {
        List<Screened> screened = books.stream().map(OrderBookScreener::screenStructure).toList();

        List<BigDecimal> midPrices = screened.stream()
                .filter(Screened.Valid.class::isInstance)
                .map(Screened.Valid.class::cast)
                .map(Screened.Valid::midPrice)
                .sorted()
                .toList();
        if (midPrices.isEmpty()) {
            return new ScreeningResult(List.of(), namesOf(screened));
        }
        BigDecimal median = median(midPrices);

        List<Screened> deviationChecked = screened.stream()
                .map(s -> s instanceof Screened.Valid(ExchangeBook book, BigDecimal mid) && exceedsLimit(mid, median)
                        ? new Screened.Invalid(book.exchange())
                        : s)
                .toList();

        return new ScreeningResult(
                deviationChecked.stream()
                        .filter(Screened.Valid.class::isInstance)
                        .map(s -> ((Screened.Valid) s).book())
                        .toList(),
                namesOf(deviationChecked));
    }

    private static Screened screenStructure(Book book) {
        return switch (book) {
            case UnreadableBook unreadable -> new Screened.Invalid(unreadable.exchange());
            case ExchangeBook(String exchange, List<PriceLevel> rawBids, List<PriceLevel> rawAsks) -> {
                List<PriceLevel> bids = rawBids.stream().filter(PriceLevel::isStrictlyPositive).toList();
                List<PriceLevel> asks = rawAsks.stream().filter(PriceLevel::isStrictlyPositive).toList();

                Optional<BigDecimal> bestBid = bids.stream().map(PriceLevel::price).max(Comparator.naturalOrder());
                Optional<BigDecimal> bestAsk = asks.stream().map(PriceLevel::price).min(Comparator.naturalOrder());

                yield bestBid.isPresent() && bestAsk.isPresent() && bestBid.get().compareTo(bestAsk.get()) < 0
                        ? new Screened.Valid(new ExchangeBook(exchange, bids, asks), midPrice(bestBid.get(), bestAsk.get()))
                        : new Screened.Invalid(exchange);
            }
        };
    }

    /** m = (bestBid + bestAsk) / 2 */
    static BigDecimal midPrice(BigDecimal bestBid, BigDecimal bestAsk) {
        return bestBid.add(bestAsk).multiply(HALF);
    }

    /** Median of a non-empty ascending list; the mean of the middle two when the count is even. */
    static BigDecimal median(List<BigDecimal> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).multiply(HALF);
    }

    /**
     * PED = 100 * |m - median| / median > 5%, rearranged to |m - median| > 0.05 * median. The
     * median is strictly positive because every screened price is.
     */
    boolean exceedsLimit(BigDecimal midPrice, BigDecimal median) {
        return midPrice.subtract(median).abs().compareTo(pedLimit.multiply(median)) > 0;
    }

    /** Names in input order. A book excluded without a readable name has nothing to report. */
    private static List<String> namesOf(List<Screened> screened) {
        return screened.stream()
                .map(s -> s instanceof Screened.Invalid(String exchange) ? exchange : null)
                .filter(Objects::nonNull)
                .toList();
    }
}
