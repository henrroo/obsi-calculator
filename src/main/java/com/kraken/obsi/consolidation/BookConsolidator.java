package com.kraken.obsi.consolidation;

import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.PriceLevel;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consolidation and uncrossing, methodology section 3. Pure and stateless.
 *
 * <ol>
 *   <li>Combine the surviving books, summing sizes at identical bid prices and, separately, at
 *       identical ask prices. Prices are identical when numerically equal, so {@code 100.0} and
 *       {@code 100.00} are the same level.</li>
 *   <li>Order bids from highest to lowest price and asks from lowest to highest.</li>
 *   <li>While the best bid is greater than or equal to the best ask, subtract the smaller of the
 *       two sizes from both levels and remove any level whose residual size is zero.</li>
 * </ol>
 *
 * The input is expected to come from {@code OrderBookScreener}: every level strictly positive.
 */
public final class BookConsolidator {

    /**
     * @throws UncrossingExhaustedException if either side has no level left once the best bid
     *                                      is no longer greater than or equal to the best ask
     * @throws IllegalArgumentException     if there are no books to consolidate
     */
    public ConsolidatedBook consolidate(List<ExchangeBook> books) throws UncrossingExhaustedException {
        if (books.isEmpty()) {
            throw new IllegalArgumentException("no books to consolidate");
        }
        Deque<PriceLevel> bids = consolidateSide(books, ExchangeBook::bids, Comparator.reverseOrder());
        Deque<PriceLevel> asks = consolidateSide(books, ExchangeBook::asks, Comparator.naturalOrder());

        uncross(bids, asks);

        return new ConsolidatedBook(List.copyOf(bids), List.copyOf(asks));
    }

    /** One side of all books as a queue of unique price levels, best price first. */
    private static Deque<PriceLevel> consolidateSide(List<ExchangeBook> books,
                                                     Function<ExchangeBook, List<PriceLevel>> side,
                                                     Comparator<BigDecimal> bestPriceFirst) {
        // A TreeMap keys by compareTo, which is what makes 100.0 and 100.00 one level;
        // BigDecimal.equals (and so a HashMap) would keep them apart.
        return books.stream()
                .flatMap(book -> side.apply(book).stream())
                .collect(Collectors.toMap(
                        PriceLevel::price, PriceLevel::size, BigDecimal::add, () -> new TreeMap<>(bestPriceFirst)))
                .entrySet().stream()
                .map(level -> new PriceLevel(level.getKey(), level.getValue()))
                .collect(Collectors.toCollection(ArrayDeque::new));
    }

    private static void uncross(Deque<PriceLevel> bids, Deque<PriceLevel> asks) throws UncrossingExhaustedException {
        while (!bids.isEmpty() && !asks.isEmpty()
                && bids.getFirst().price().compareTo(asks.getFirst().price()) >= 0) {
            PriceLevel bid = bids.removeFirst();
            PriceLevel ask = asks.removeFirst();
            BigDecimal matched = bid.size().min(ask.size());

            pushResidual(bids, bid, matched);
            pushResidual(asks, ask, matched);
        }
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new UncrossingExhaustedException("uncrossing exhausted " + describeExhausted(bids, asks));
        }
    }

    /** Puts the level back at the top of its side unless the match consumed it entirely. */
    private static void pushResidual(Deque<PriceLevel> side, PriceLevel level, BigDecimal matched) {
        BigDecimal residual = level.size().subtract(matched);
        if (residual.signum() > 0) {
            side.addFirst(new PriceLevel(level.price(), residual));
        }
    }

    private static String describeExhausted(Deque<PriceLevel> bids, Deque<PriceLevel> asks) {
        if (bids.isEmpty() && asks.isEmpty()) {
            return "both sides of the consolidated book";
        }
        return bids.isEmpty() ? "the bid side of the consolidated book" : "the ask side of the consolidated book";
    }
}
