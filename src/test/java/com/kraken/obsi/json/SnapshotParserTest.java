package com.kraken.obsi.json;

import com.kraken.obsi.model.Book;
import com.kraken.obsi.model.CalculationTime;
import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.IndexResult;
import com.kraken.obsi.model.PriceLevel;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.model.UnreadableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotParserTest {

    private static final String SAMPLE = """
            {
              "calculationTime": "2026-10-27T12:00:00Z",
              "books": [
                {
                  "exchange": "Alpha",
                  "bids": [[100.0, 1.2], [99.8, 1.3], [99.0, 2.0], [98.0, 2.0]],
                  "asks": [[100.4, 1.1], [100.6, 1.4], [101.2, 2.0], [102.0, 2.0]]
                },
                {
                  "exchange": "Beta",
                  "bids": [[100.2, 0.8], [99.8, 1.0], [99.1, 2.0], [98.1, 2.0]],
                  "asks": [[100.3, 0.8], [100.6, 1.0], [101.1, 2.0], [102.1, 2.0]]
                },
                {
                  "exchange": "Gamma",
                  "bids": [[100.6, 1.0]],
                  "asks": [[100.5, 1.0]]
                },
                {
                  "exchange": "Delta",
                  "bids": [[149.0, 1.0]],
                  "asks": [[151.0, 1.0]]
                }
              ]
            }
            """;

    private final SnapshotParser parser = new SnapshotParser();

    private static PriceLevel level(String price, String size) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(size));
    }

    private static String snapshotWithBooks(String books) {
        return """
                {"calculationTime": "2026-10-27T12:00:00Z", "books": %s}
                """.formatted(books);
    }

    @Nested
    @DisplayName("parsing a well-formed snapshot")
    class WellFormed {

        @Test
        void readsCalculationTimeAsUtcInstantAndKeepsOriginalText() throws Exception {
            Snapshot snapshot = parser.parse(SAMPLE);

            assertEquals(Instant.parse("2026-10-27T12:00:00Z"), snapshot.calculationTime().instant());
            assertEquals("2026-10-27T12:00:00Z", snapshot.calculationTime().text());
        }

        @Test
        void readsBooksInInputOrder() throws Exception {
            Snapshot snapshot = parser.parse(SAMPLE);

            assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"),
                    snapshot.books().stream().map(Book::exchange).toList());
            assertTrue(snapshot.books().stream().allMatch(ExchangeBook.class::isInstance));
        }

        @Test
        void readsLevelsAsExactDecimalsInInputOrder() throws Exception {
            ExchangeBook alpha = assertInstanceOf(ExchangeBook.class, parser.parse(SAMPLE).books().getFirst());

            assertEquals(List.of(level("100.0", "1.2"), level("99.8", "1.3"), level("99.0", "2.0"), level("98.0", "2.0")),
                    alpha.bids());
            assertEquals(List.of(level("100.4", "1.1"), level("100.6", "1.4"), level("101.2", "2.0"), level("102.0", "2.0")),
                    alpha.asks());
        }

        @Test
        void doesNotLosePrecisionThroughDouble() throws Exception {
            Snapshot snapshot = parser.parse(snapshotWithBooks("""
                    [{"exchange": "A", "bids": [[0.1234567890123456789012345, 1]], "asks": [[1e2, 3]]}]
                    """));
            ExchangeBook book = assertInstanceOf(ExchangeBook.class, snapshot.books().getFirst());

            assertEquals(new BigDecimal("0.1234567890123456789012345"), book.bids().getFirst().price());
            assertEquals(0, new BigDecimal("100").compareTo(book.asks().getFirst().price()));
        }

        @Test
        void acceptsEmptyBooksArray() throws Exception {
            assertEquals(List.of(), parser.parse(snapshotWithBooks("[]")).books());
        }

        @Test
        void ignoresUnknownFields() throws Exception {
            Snapshot snapshot = parser.parse("""
                    {"calculationTime": "2026-10-27T12:00:00Z", "source": "test",
                     "books": [{"exchange": "A", "venue": 1, "bids": [[1, 1]], "asks": [[2, 1]]}]}
                    """);

            assertEquals(new ExchangeBook("A", List.of(level("1", "1")), List.of(level("2", "1"))),
                    snapshot.books().getFirst());
        }
    }

    @Nested
    @DisplayName("calculationTime handling")
    class CalculationTimes {

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-27T12:00:00.000Z", "2026-10-27T12:00:00.123456Z", "2026-10-27T12:00:00+00:00"})
        void acceptsUtcVariantsAndEchoesThemVerbatim(String time) throws Exception {
            Snapshot snapshot = parser.parse("""
                    {"calculationTime": "%s", "books": []}
                    """.formatted(time));

            assertEquals(time, snapshot.calculationTime().text());
            assertEquals(Instant.parse(time), snapshot.calculationTime().instant());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "\"2026-10-27T12:00:00+02:00\"",  // not UTC
                "\"2026-10-27T12:00:00\"",        // no offset
                "\"2026-10-27\"",                 // date only
                "\"2026-13-40T12:00:00Z\"",       // impossible date
                "\"yesterday\"",
                "\"\"",
                "1793102400",
                "null"
        })
        void rejectsNonConformingCalculationTime(String jsonValue) {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse("""
                    {"calculationTime": %s, "books": []}
                    """.formatted(jsonValue)));

            assertTrue(e.calculationTime().isEmpty());
        }

        @Test
        void rejectsMissingCalculationTime() {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse("""
                    {"books": []}
                    """));

            assertEquals("missing required field: calculationTime", e.getMessage());
            assertTrue(e.calculationTime().isEmpty());
        }
    }

    @Nested
    @DisplayName("malformed top-level input")
    class MalformedTopLevel {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "not json", "{\"calculationTime\": ", "[]", "42", "null", "{} trailing"})
        void rejectsInputThatIsNotASingleJsonObject(String json) {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse(json));

            assertTrue(e.calculationTime().isEmpty());
        }

        @Test
        void missingBooksStillReportsCalculationTime() {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse("""
                    {"calculationTime": "2026-10-27T12:00:00Z"}
                    """));

            assertEquals("missing required field: books", e.getMessage());
            assertEquals("2026-10-27T12:00:00Z", e.calculationTime().orElseThrow().text());
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "\"Alpha\"", "null", "7"})
        void booksThatIsNotAnArrayStillReportsCalculationTime(String books) {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse(snapshotWithBooks(books)));

            assertEquals("books must be an array", e.getMessage());
            assertTrue(e.calculationTime().isPresent());
        }
    }

    @Nested
    @DisplayName("lenient handling inside books")
    class InsideBooks {

        @Test
        void discardsEntriesThatAreNotPairsOfNumbersButKeepsNonPositiveOnes() throws Exception {
            Snapshot snapshot = parser.parse(snapshotWithBooks("""
                    [{
                      "exchange": "A",
                      "bids": [[100.0, 1.0], ["99.9", 1.0], [99.8, "1.0"], [99.7, null], [99.6, true],
                               [0, 1.0], [99.5, 0], [-99.4, 1.0], [99.3, -1.0], [99.2, 0.0],
                               [99.1], [99.0, 1.0, 5], [], 98.9, null, {"price": 98.8, "size": 1}, [98.7, 2.5]],
                      "asks": [[null, null]]
                    }]
                    """));
            ExchangeBook book = assertInstanceOf(ExchangeBook.class, snapshot.books().getFirst());

            assertEquals(List.of(level("100.0", "1.0"),
                            level("0", "1.0"), level("99.5", "0"), level("-99.4", "1.0"), level("99.3", "-1.0"),
                            level("99.2", "0.0"), level("98.7", "2.5")),
                    book.bids());
            assertEquals(List.of(), book.asks());
        }

        @Test
        void unreadableBooksAreKeptInPlaceWithTheirNameWhenAvailable() throws Exception {
            Snapshot snapshot = parser.parse(snapshotWithBooks("""
                    [
                      {"exchange": "NoAsks", "bids": [[1, 1]]},
                      {"exchange": "BidsNotArray", "bids": {}, "asks": [[2, 1]]},
                      {"exchange": "Good", "bids": [[1, 1]], "asks": [[2, 1]]},
                      {"bids": [[1, 1]], "asks": [[2, 1]]},
                      {"exchange": 5, "bids": [[1, 1]], "asks": [[2, 1]]},
                      "garbage",
                      null,
                      []
                    ]
                    """));

            assertEquals(List.of(
                            new UnreadableBook("NoAsks"),
                            new UnreadableBook("BidsNotArray"),
                            new ExchangeBook("Good", List.of(level("1", "1")), List.of(level("2", "1"))),
                            new UnreadableBook(null),
                            new UnreadableBook(null),
                            new UnreadableBook(null),
                            new UnreadableBook(null),
                            new UnreadableBook(null)),
                    snapshot.books());
        }
    }

    @Nested
    @DisplayName("unlockEvents (--new-ped input)")
    class UnlockEvents {

        private final SnapshotParser newPedParser = SnapshotParser.withUnlockEvents();

        private static String withUnlockEvents(String unlockEvents) {
            return """
                    {"calculationTime": "2026-10-27T12:00:00Z", "unlockEvents": %s, "books": []}
                    """.formatted(unlockEvents);
        }

        @Test
        void readsUnlockStartsInOrder() throws Exception {
            Snapshot snapshot = newPedParser.parse(withUnlockEvents("""
                    ["2026-10-26T18:00:00Z", "2026-11-01T00:00:00.000Z"]"""));

            assertEquals(List.of(Instant.parse("2026-10-26T18:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")),
                    snapshot.unlockEvents());
        }

        @ParameterizedTest
        @ValueSource(strings = {"[]", "null"})
        void emptyOrNullMeansNoUnlocks(String unlockEvents) throws Exception {
            assertEquals(List.of(), newPedParser.parse(withUnlockEvents(unlockEvents)).unlockEvents());
        }

        @Test
        void absentMeansNoUnlocks() throws Exception {
            assertEquals(List.of(), newPedParser.parse(SAMPLE).unlockEvents());
        }

        @Test
        void proposalExampleFileIsReadable() throws Exception {
            Snapshot snapshot = newPedParser.parse(Path.of("data/new-ped.json"));

            assertEquals(List.of(Instant.parse("2026-10-26T18:00:00Z")), snapshot.unlockEvents());
            assertEquals(4, snapshot.books().size());
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "\"2026-10-26T18:00:00Z\"                    | unlockEvents must be an array of ISO-8601 UTC strings",
                "{}                                          | unlockEvents must be an array of ISO-8601 UTC strings",
                "[1793102400]                                | unlockEvents[0] must be a string",
                "[\"2026-10-26T18:00:00Z\", null]            | unlockEvents[1] must be a string",
                "[\"2026-10-26T18:00:00+01:00\"]             | unlockEvents[0] is not in UTC: 2026-10-26T18:00:00+01:00",
                "[\"2026-10-26\"]                            | unlockEvents[0] is not an ISO-8601 date-time: 2026-10-26"
        })
        void malformedUnlockEventsAreInputErrorsThatStillEchoTheCalculationTime(String unlockEvents, String reason) {
            var e = assertThrows(SnapshotParseException.class, () -> newPedParser.parse(withUnlockEvents(unlockEvents)));

            assertEquals(reason, e.getMessage());
            assertEquals("2026-10-27T12:00:00Z", e.calculationTime().orElseThrow().text());
        }

        @Test
        void baselineParserIgnoresUnlockEventsEvenWhenMalformed() throws Exception {
            Snapshot snapshot = parser.parse(withUnlockEvents("\"garbage\""));

            assertEquals(List.of(), snapshot.unlockEvents());
        }
    }

    @Nested
    @DisplayName("serializing results")
    class Serializing {

        private final CalculationTime time = CalculationTime.parse("2026-10-27T12:00:00Z");

        @Test
        void publishedResultMatchesTheSpecifiedShape() {
            IndexResult result = IndexResult.published(time, new BigDecimal("100.00"), new BigDecimal("1"), List.of());

            assertEquals("""
                    {"index":"OBSI","calculationTime":"2026-10-27T12:00:00Z","status":"published",\
                    "value":"100.00","metadata":{"utilizedDepth":"1","excludedExchanges":[]}}""",
                    parser.serialize(result));
        }

        @Test
        void publishedValueIsPrintedAsGivenWithoutScientificNotation() {
            // Rounding belongs to IndexCalculator; serialization must not alter the scale.
            assertEquals("100.10", IndexResult.published(time, new BigDecimal("100.10"), BigDecimal.ONE, List.of()).value());
            assertEquals("0.10", IndexResult.published(time, new BigDecimal("0.10"), BigDecimal.ONE, List.of()).value());
            BigDecimal million = new BigDecimal("1E+6").setScale(2); // as IndexCalculator would produce it
            assertEquals("1000000.00", IndexResult.published(time, million, BigDecimal.ONE, List.of()).value());
        }

        @Test
        void utilizedDepthIsPlainWithoutTrailingZerosAndExclusionsKeepOrder() {
            IndexResult.Published result =
                    IndexResult.published(time, BigDecimal.TEN, new BigDecimal("20.0"), List.of("Gamma", "Delta"));

            assertEquals("20", result.metadata().utilizedDepth());
            assertTrue(parser.serialize(result).endsWith("\"excludedExchanges\":[\"Gamma\",\"Delta\"]}}"));
        }

        @Test
        void failedResultEchoesCalculationTime() {
            assertEquals("""
                    {"index":"OBSI","calculationTime":"2026-10-27T12:00:00Z","status":"failed","reason":"no books"}""",
                    parser.serialize(IndexResult.failed(time, "no books")));
        }

        @Test
        void failedResultWritesNullCalculationTimeForNonConformingInput() {
            assertEquals("""
                    {"index":"OBSI","calculationTime":null,"status":"failed","reason":"input is not valid JSON"}""",
                    parser.serialize(IndexResult.failed(null, "input is not valid JSON")));
        }

        @Test
        void parseFailureFlowsIntoFailedResult() {
            var e = assertThrows(SnapshotParseException.class, () -> parser.parse("""
                    {"calculationTime": "2026-10-27T12:00:00Z"}
                    """));

            assertEquals("""
                    {"index":"OBSI","calculationTime":"2026-10-27T12:00:00Z","status":"failed",\
                    "reason":"missing required field: books"}""",
                    parser.serialize(IndexResult.failed(e.calculationTime().orElse(null), e.getMessage())));
        }
    }
}
