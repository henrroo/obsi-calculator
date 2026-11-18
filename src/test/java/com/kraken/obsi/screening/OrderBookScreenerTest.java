package com.kraken.obsi.screening;

import com.kraken.obsi.model.Book;
import com.kraken.obsi.model.CalculationTime;
import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.PriceLevel;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.model.UnreadableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookScreenerTest {

    private final OrderBookScreener screener = new OrderBookScreener();

    private static PriceLevel level(String price, String size) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static ExchangeBook book(String exchange, List<PriceLevel> bids, List<PriceLevel> asks) {
        return new ExchangeBook(exchange, bids, asks);
    }

    /** A one-level book whose mid-price is exactly {@code mid} (spread of 0.02 around it). */
    private static ExchangeBook bookWithMid(String exchange, String mid) {
        BigDecimal m = new BigDecimal(mid);
        BigDecimal halfSpread = new BigDecimal("0.01");
        return book(exchange,
                List.of(new PriceLevel(m.subtract(halfSpread), BigDecimal.ONE)),
                List.of(new PriceLevel(m.add(halfSpread), BigDecimal.ONE)));
    }

    private static List<String> names(ScreeningResult result) {
        return result.survivingBooks().stream().map(ExchangeBook::exchange).toList();
    }

    @Nested
    @DisplayName("entry screen: non-positive prices and sizes")
    class EntryScreen {

        @Test
        void nonPositiveEntriesAreDiscardedFromTheSurvivingBook() {
            ExchangeBook raw = book("A",
                    List.of(level("100", "1"), level("0", "1"), level("-99", "1"), level("98", "0"), level("97", "-2"),
                            level("96", "3")),
                    List.of(level("101", "1"), level("102", "0.0"), level("-103", "-1"), level("104", "2")));

            ScreeningResult result = screener.screen(List.of(raw));

            assertEquals(List.of(book("A",
                            List.of(level("100", "1"), level("96", "3")),
                            List.of(level("101", "1"), level("104", "2")))),
                    result.survivingBooks());
            assertEquals(List.of(), result.excludedExchanges());
        }

        @Test
        void bookIsExcludedWhenDiscardingLeavesASideEmpty() {
            ExchangeBook noValidBids = book("NoBids", List.of(level("100", "0"), level("-1", "1")), List.of(level("101", "1")));
            ExchangeBook noValidAsks = book("NoAsks", List.of(level("100", "1")), List.of(level("0", "1")));

            ScreeningResult result = screener.screen(List.of(noValidBids, bookWithMid("Good", "100"), noValidAsks));

            assertEquals(List.of("Good"), names(result));
            assertEquals(List.of("NoBids", "NoAsks"), result.excludedExchanges());
        }

        @Test
        void discardHappensBeforeBestPricesAreDetermined() {
            // Kept as-is, the zero-priced ask would be the best ask and the zero-sized bid the
            // best bid; either would make the book look crossed.
            ExchangeBook zeroPricedAsk = book("ZeroAsk", List.of(level("100", "1")), List.of(level("0", "1"), level("101", "1")));
            ExchangeBook zeroSizedBid = book("ZeroBid", List.of(level("102", "0"), level("100", "1")), List.of(level("101", "1")));

            ScreeningResult result = screener.screen(List.of(zeroPricedAsk, zeroSizedBid));

            assertEquals(List.of("ZeroAsk", "ZeroBid"), names(result));
            assertEquals(List.of(), result.excludedExchanges());
        }
    }

    @Nested
    @DisplayName("structural screen")
    class StructuralScreen {

        @Test
        void bookWithBestBidAboveBestAskIsExcluded() {
            ExchangeBook crossed = book("Crossed", List.of(level("100.6", "1")), List.of(level("100.5", "1")));

            ScreeningResult result = screener.screen(List.of(bookWithMid("Good", "100"), crossed));

            assertEquals(List.of("Good"), names(result));
            assertEquals(List.of("Crossed"), result.excludedExchanges());
        }

        @Test
        void bookWithBestBidEqualToBestAskIsExcluded() {
            // Same value at different scales: comparison must be numeric, not BigDecimal.equals.
            ExchangeBook locked = book("Locked", List.of(level("100.50", "1")), List.of(level("100.5", "1")));

            ScreeningResult result = screener.screen(List.of(locked, bookWithMid("Good", "100")));

            assertEquals(List.of("Good"), names(result));
            assertEquals(List.of("Locked"), result.excludedExchanges());
        }

        @Test
        void bestPricesAreFoundRegardlessOfLevelOrder() {
            // Best bid 100.5 >= best ask 100.4, though neither is listed first.
            ExchangeBook crossed = book("Unsorted",
                    List.of(level("99", "1"), level("100.5", "1"), level("98", "1")),
                    List.of(level("102", "1"), level("100.4", "1"), level("101", "1")));

            assertEquals(List.of("Unsorted"), screener.screen(List.of(crossed)).excludedExchanges());
        }

        @Test
        void emptyAndUnreadableBooksAreExcluded() {
            List<Book> books = List.of(
                    book("Empty", List.of(), List.of()),
                    new UnreadableBook("Broken"),
                    bookWithMid("Good", "100"));

            ScreeningResult result = screener.screen(books);

            assertEquals(List.of("Good"), names(result));
            assertEquals(List.of("Empty", "Broken"), result.excludedExchanges());
        }

        @Test
        void unreadableBookWithoutANameIsDroppedButNotReported() {
            ScreeningResult result = screener.screen(List.of(new UnreadableBook(null), bookWithMid("Good", "100")));

            assertEquals(List.of("Good"), names(result));
            assertEquals(List.of(), result.excludedExchanges());
        }
    }

    @Nested
    @DisplayName("deviation screen (PED)")
    class DeviationScreen {

        @Test
        void bookAtExactlyFivePercentPasses() {
            // Median mid = 100; 105 and 95 are each exactly 5% away.
            ScreeningResult result = screener.screen(List.of(
                    bookWithMid("Low", "95"),
                    bookWithMid("M1", "100"),
                    bookWithMid("M2", "100"),
                    bookWithMid("M3", "100"),
                    bookWithMid("High", "105")));

            assertEquals(List.of("Low", "M1", "M2", "M3", "High"), names(result));
            assertEquals(List.of(), result.excludedExchanges());
        }

        @Test
        void bookJustAboveFivePercentIsExcluded() {
            ScreeningResult result = screener.screen(List.of(
                    bookWithMid("Low", "94.9999"),
                    bookWithMid("M1", "100"),
                    bookWithMid("M2", "100"),
                    bookWithMid("M3", "100"),
                    bookWithMid("High", "105.0001")));

            assertEquals(List.of("M1", "M2", "M3"), names(result));
            assertEquals(List.of("Low", "High"), result.excludedExchanges());
        }

        @Test
        void bookFarFromTheMedianIsExcluded() {
            ScreeningResult result = screener.screen(List.of(
                    bookWithMid("A", "100"), bookWithMid("B", "100.2"), bookWithMid("Outlier", "150")));

            assertEquals(List.of("A", "B"), names(result));
            assertEquals(List.of("Outlier"), result.excludedExchanges());
        }

        @Test
        void evenCountUsesTheMeanOfTheMiddleTwoMidPrices() {
            // Median = 105; both books are 5/105 = 4.76% away and pass.
            assertEquals(List.of("A", "B"),
                    names(screener.screen(List.of(bookWithMid("A", "100"), bookWithMid("B", "110")))));

            // Median = 105.5; both books are 5.5/105.5 = 5.21% away and are excluded.
            ScreeningResult bothOut = screener.screen(List.of(bookWithMid("A", "100"), bookWithMid("B", "111")));
            assertEquals(List.of(), bothOut.survivingBooks());
            assertEquals(List.of("A", "B"), bothOut.excludedExchanges());
        }

        @Test
        void medianIsTakenOverStructurallyValidBooksOnly() {
            // With the crossed book's would-be mid (200) the median would be 110 and A (100)
            // would be 9% away. Without it the median is 105 and both A and B pass.
            ExchangeBook crossed = book("Crossed", List.of(level("201", "1")), List.of(level("199", "1")));

            ScreeningResult result = screener.screen(List.of(bookWithMid("A", "100"), bookWithMid("B", "110"), crossed));

            assertEquals(List.of("A", "B"), names(result));
            assertEquals(List.of("Crossed"), result.excludedExchanges());
        }

        @Test
        void medianIsNotRecomputedAfterAnExclusion() {
            // Median of {100, 104, 110} = 104: 110 is 5.77% away and goes. Against the remaining
            // pair's median (102) nothing else changes, but against a recomputed median 100 must
            // not be re-tested; one pass only.
            ScreeningResult result = screener.screen(List.of(
                    bookWithMid("A", "100"), bookWithMid("B", "104"), bookWithMid("C", "110")));

            assertEquals(List.of("A", "B"), names(result));
            assertEquals(List.of("C"), result.excludedExchanges());
        }

        @Test
        void singleValidBookIsItsOwnMedianAndPasses() {
            assertEquals(List.of("Only"), names(screener.screen(List.of(bookWithMid("Only", "123.45")))));
        }

        @Test
        void midPriceUsesBestBidAndBestAskOnly() {
            // Mid = (100 + 110) / 2 = 105 against a median of 100: exactly 5%, passes. Deeper
            // levels must not move it.
            ExchangeBook deep = book("Deep",
                    List.of(level("100", "1"), level("1", "1000")),
                    List.of(level("110", "1"), level("9999", "1000")));

            ScreeningResult result = screener.screen(List.of(deep, bookWithMid("M1", "100"), bookWithMid("M2", "100")));

            assertEquals(List.of("Deep", "M1", "M2"), names(result));
        }
    }

    @Nested
    @DisplayName("explicit PED limit (used by --new-ped)")
    class ExplicitLimit {

        @Test
        void tighterLimitExcludesBooksTheBaselineKeeps() {
            List<Book> books = List.of(
                    bookWithMid("A", "100"), bookWithMid("B", "100"), bookWithMid("C", "100"),
                    bookWithMid("OnePointFive", "101.5"), bookWithMid("Three", "103"));

            assertEquals(List.of(), screener.screen(books).excludedExchanges());
            assertEquals(List.of("Three"), new OrderBookScreener(new BigDecimal("0.02")).screen(books).excludedExchanges());
            assertEquals(List.of("OnePointFive", "Three"),
                    new OrderBookScreener(new BigDecimal("0.01")).screen(books).excludedExchanges());
        }

        @Test
        void exactlyAtTheExplicitLimitPasses() {
            List<Book> books = List.of(bookWithMid("A", "100"), bookWithMid("B", "100"), bookWithMid("AtTwo", "102"));

            assertEquals(List.of(), new OrderBookScreener(new BigDecimal("0.02")).screen(books).excludedExchanges());
        }

        @Test
        void rejectsNonPositiveLimit() {
            assertThrows(IllegalArgumentException.class, () -> new OrderBookScreener(BigDecimal.ZERO));
        }
    }

    @Nested
    @DisplayName("results")
    class Results {

        @Test
        void sampleSnapshotExcludesCrossedAndDeviatingBooksInInputOrder() {
            Snapshot snapshot = new Snapshot(CalculationTime.parse("2026-10-27T12:00:00Z"), List.of(
                    book("Alpha",
                            List.of(level("100.0", "1.2"), level("99.8", "1.3"), level("99.0", "2.0"), level("98.0", "2.0")),
                            List.of(level("100.4", "1.1"), level("100.6", "1.4"), level("101.2", "2.0"), level("102.0", "2.0"))),
                    book("Beta",
                            List.of(level("100.2", "0.8"), level("99.8", "1.0"), level("99.1", "2.0"), level("98.1", "2.0")),
                            List.of(level("100.3", "0.8"), level("100.6", "1.0"), level("101.1", "2.0"), level("102.1", "2.0"))),
                    book("Gamma", List.of(level("100.6", "1.0")), List.of(level("100.5", "1.0"))),
                    book("Delta", List.of(level("149.0", "1.0")), List.of(level("151.0", "1.0")))));

            ScreeningResult result = screener.screen(snapshot);

            assertEquals(snapshot.books().subList(0, 2), result.survivingBooks());
            assertEquals(List.of("Gamma", "Delta"), result.excludedExchanges());
        }

        @Test
        void exclusionsFromDifferentScreensAreReportedInInputOrder() {
            ScreeningResult result = screener.screen(List.of(
                    bookWithMid("Outlier", "150"),
                    bookWithMid("A", "100"),
                    new UnreadableBook("Broken"),
                    bookWithMid("B", "100"),
                    book("Crossed", List.of(level("2", "1")), List.of(level("1", "1"))),
                    bookWithMid("C", "100")));

            assertEquals(List.of("A", "B", "C"), names(result));
            assertEquals(List.of("Outlier", "Broken", "Crossed"), result.excludedExchanges());
        }

        @Test
        void noBooksOrNoValidBooksYieldsNoSurvivors() {
            assertEquals(new ScreeningResult(List.of(), List.of()), screener.screen(List.of()));

            ScreeningResult allInvalid = screener.screen(List.of(new UnreadableBook("X"), book("Y", List.of(), List.of())));
            assertEquals(List.of(), allInvalid.survivingBooks());
            assertEquals(List.of("X", "Y"), allInvalid.excludedExchanges());
        }
    }

    @Nested
    @DisplayName("exact arithmetic")
    class Arithmetic {

        @Test
        void midPriceIsExact() {
            assertEquals(0, new BigDecimal("100.25").compareTo(
                    OrderBookScreener.midPrice(new BigDecimal("100.2"), new BigDecimal("100.3"))));
            assertEquals(0, new BigDecimal("0.15").compareTo(
                    OrderBookScreener.midPrice(new BigDecimal("0.1"), new BigDecimal("0.2"))));
        }

        @Test
        void deviationBoundaryIsExactForValuesDoubleCannotRepresent() {
            BigDecimal median = new BigDecimal("0.3");

            assertFalse(screener.exceedsLimit(new BigDecimal("0.315"), median));
            assertFalse(screener.exceedsLimit(new BigDecimal("0.285"), median));
            assertTrue(screener.exceedsLimit(new BigDecimal("0.3150000000000000000001"), median));
            assertTrue(screener.exceedsLimit(new BigDecimal("0.2849999999999999999999"), median));
        }
    }
}
