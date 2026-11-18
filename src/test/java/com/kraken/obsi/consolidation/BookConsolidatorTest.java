package com.kraken.obsi.consolidation;

import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.PriceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookConsolidatorTest {

    private final BookConsolidator consolidator = new BookConsolidator();

    private static PriceLevel level(String price, String size) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static ExchangeBook book(String exchange, List<PriceLevel> bids, List<PriceLevel> asks) {
        return new ExchangeBook(exchange, bids, asks);
    }

    /** Levels as "price@size" with trailing zeros stripped, so assertions compare numeric values, not scales. */
    private static List<String> render(List<PriceLevel> levels) {
        return levels.stream()
                .map(l -> l.price().stripTrailingZeros().toPlainString() + "@" + l.size().stripTrailingZeros().toPlainString())
                .toList();
    }

    private static void assertBook(ConsolidatedBook actual, List<String> expectedBids, List<String> expectedAsks) {
        assertEquals(expectedBids, render(actual.bids()), "bids");
        assertEquals(expectedAsks, render(actual.asks()), "asks");
    }

    @Nested
    @DisplayName("consolidation")
    class Consolidation {

        @Test
        void sumsSizesAtIdenticalPricesAcrossBooks() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "1.2"), level("99", "1")), List.of(level("101", "0.5"), level("102", "1"))),
                    book("B", List.of(level("100", "0.8"), level("98", "2")), List.of(level("101", "0.7"), level("103", "3"))),
                    book("C", List.of(level("100", "0.1")), List.of(level("102", "0.25")))));

            assertBook(result,
                    List.of("100@2.1", "99@1", "98@2"),
                    List.of("101@1.2", "102@1.25", "103@3"));
        }

        @Test
        void treatsNumericallyEqualPricesAsIdenticalRegardlessOfScale() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100.0", "1")), List.of(level("101", "1"))),
                    book("B", List.of(level("100.00", "2")), List.of(level("101.000", "2"))),
                    book("C", List.of(level("1E+2", "4")), List.of(level("101.0", "4")))));

            assertBook(result, List.of("100@7"), List.of("101@7"));
        }

        @Test
        void sumsDuplicatePricesWithinASingleBook() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "1"), level("100", "2")), List.of(level("101", "3"), level("101", "4")))));

            assertBook(result, List.of("100@3"), List.of("101@7"));
        }

        @Test
        void sumsExactlyWithoutFloatingPointError() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "0.1")), List.of(level("101", "0.1"))),
                    book("B", List.of(level("100", "0.2")), List.of(level("101", "0.7")))));

            // In double arithmetic 0.1 + 0.2 = 0.30000000000000004 and 0.1 + 0.7 = 0.7999999999999999.
            assertEquals(new BigDecimal("0.3"), result.bids().getFirst().size());
            assertEquals(new BigDecimal("0.8"), result.asks().getFirst().size());
        }

        @Test
        void ordersBidsDescendingAndAsksAscendingWhateverTheInputOrder() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("98", "1"), level("100", "1")), List.of(level("104", "1"), level("101", "1"))),
                    book("B", List.of(level("99.5", "1"), level("97", "1")), List.of(level("102.5", "1"), level("103", "1")))));

            assertBook(result,
                    List.of("100@1", "99.5@1", "98@1", "97@1"),
                    List.of("101@1", "102.5@1", "103@1", "104@1"));
        }

        @Test
        void rejectsAnEmptyListOfBooks() {
            assertThrows(IllegalArgumentException.class, () -> consolidator.consolidate(List.of()));
        }
    }

    @Nested
    @DisplayName("uncrossing")
    class Uncrossing {

        @Test
        void cleanBookIsLeftUntouched() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "1"), level("99", "2")), List.of(level("100.01", "1"), level("101", "2")))));

            assertBook(result, List.of("100@1", "99@2"), List.of("100.01@1", "101@2"));
        }

        @Test
        void sampleBooksConsolidateWithoutUncrossing() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("Alpha",
                            List.of(level("100.0", "1.2"), level("99.8", "1.3"), level("99.0", "2.0"), level("98.0", "2.0")),
                            List.of(level("100.4", "1.1"), level("100.6", "1.4"), level("101.2", "2.0"), level("102.0", "2.0"))),
                    book("Beta",
                            List.of(level("100.2", "0.8"), level("99.8", "1.0"), level("99.1", "2.0"), level("98.1", "2.0")),
                            List.of(level("100.3", "0.8"), level("100.6", "1.0"), level("101.1", "2.0"), level("102.1", "2.0")))));

            assertBook(result,
                    List.of("100.2@0.8", "100@1.2", "99.8@2.3", "99.1@2", "99@2", "98.1@2", "98@2"),
                    List.of("100.3@0.8", "100.4@1.1", "100.6@2.4", "101.1@2", "101.2@2", "102@2", "102.1@2"));
        }

        @Test
        void equalSizesAtTheCrossDropBothLevels() throws Exception {
            // Each book is fine on its own; together A's bid (101) crosses B's ask (100).
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "1.5"), level("98", "1")), List.of(level("102", "1"))),
                    book("B", List.of(level("99", "1")), List.of(level("100", "1.5"), level("103", "1")))));

            assertBook(result, List.of("99@1", "98@1"), List.of("102@1", "103@1"));
        }

        @Test
        void equalSizesAreDetectedNumericallyAcrossScalesAndSums() throws Exception {
            // Bid 0.3 against asks summing 0.1 + 0.2 at one price: residual must be exactly zero.
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "0.30"), level("99", "1")), List.of(level("100", "0.1"), level("102", "1"))),
                    book("B", List.of(level("98", "1")), List.of(level("100", "0.2")))));

            assertBook(result, List.of("99@1", "98@1"), List.of("102@1"));
        }

        @Test
        void bidEqualToAskCountsAsCrossed() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "1"), level("99", "1")), List.of(level("101", "1"))),
                    book("B", List.of(level("98", "1")), List.of(level("100.0", "1"), level("102", "1")))));

            assertBook(result, List.of("99@1", "98@1"), List.of("101@1", "102@1"));
        }

        @Test
        void largerBidKeepsItsResidualAtTheTopOfTheBook() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "3"), level("99", "1")), List.of(level("100", "1"), level("102", "1")))));

            assertBook(result, List.of("101@2", "99@1"), List.of("102@1"));
        }

        @Test
        void largerAskKeepsItsResidualAtTheTopOfTheBook() throws Exception {
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "1"), level("99", "1")), List.of(level("100", "3"), level("102", "1")))));

            assertBook(result, List.of("99@1"), List.of("100@2", "102@1"));
        }

        @Test
        void repeatsUntilTheBestBidIsBelowTheBestAsk() throws Exception {
            // 102@1   vs 100@1.5   -> bid gone,        ask 100@0.5
            // 101@2   vs 100@0.5   -> bid 101@1.5,     ask gone
            // 101@1.5 vs 100.5@1   -> bid 101@0.5,     ask gone
            // 101@0.5 vs 103@4     -> not crossed, stop
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A",
                            List.of(level("102", "1"), level("101", "2"), level("99", "5")),
                            List.of(level("100", "1.5"), level("100.5", "1"), level("103", "4")))));

            assertBook(result, List.of("101@0.5", "99@5"), List.of("103@4"));
        }

        @Test
        void uncrossesAgainstSizesSummedAcrossBooks() throws Exception {
            // Consolidated top: bid 101@3 (1 + 2) against ask 100@2.5 (2 + 0.5).
            ConsolidatedBook result = consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "1"), level("99", "1")), List.of(level("100", "2"), level("102", "1"))),
                    book("B", List.of(level("101", "2")), List.of(level("100", "0.5")))));

            assertBook(result, List.of("101@0.5", "99@1"), List.of("102@1"));
        }
    }

    @Nested
    @DisplayName("exhaustion")
    class Exhaustion {

        @Test
        void throwsWhenTheAskSideIsExhausted() {
            var e = assertThrows(UncrossingExhaustedException.class, () -> consolidator.consolidate(List.of(
                    book("A", List.of(level("105", "10"), level("99", "1")), List.of(level("100", "1"), level("101", "2"))))));

            assertEquals("uncrossing exhausted the ask side of the consolidated book", e.getMessage());
        }

        @Test
        void throwsWhenTheBidSideIsExhausted() {
            var e = assertThrows(UncrossingExhaustedException.class, () -> consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "1"), level("100", "2")), List.of(level("95", "10"), level("110", "1"))))));

            assertEquals("uncrossing exhausted the bid side of the consolidated book", e.getMessage());
        }

        @Test
        void throwsWhenBothSidesAreExhaustedByTheSameMatch() {
            var e = assertThrows(UncrossingExhaustedException.class, () -> consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "2")), List.of(level("100", "2"))))));

            assertEquals("uncrossing exhausted both sides of the consolidated book", e.getMessage());
        }

        @Test
        void throwsEvenWhenTheOtherSideStillHasDepth() {
            // Asks run out while a large, perfectly good bid ladder remains.
            assertThrows(UncrossingExhaustedException.class, () -> consolidator.consolidate(List.of(
                    book("A", List.of(level("101", "1"), level("90", "100"), level("80", "100")), List.of(level("100", "1"))))));
        }

        @Test
        void throwsWhenASideIsEmptyToBeginWith() {
            assertThrows(UncrossingExhaustedException.class, () -> consolidator.consolidate(List.of(
                    book("A", List.of(level("100", "1")), List.of()))));
        }
    }
}
