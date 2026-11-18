package com.kraken.obsi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end runs over the fixtures in {@code data/}, asserting the exact bytes on each stream and
 * the exit status. Expected values were cross-checked with an independent reference implementation.
 */
class CliTest {

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = new Cli().run(List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String published(String time, String value, String depth, String excluded) {
        return """
                {"index":"OBSI","calculationTime":%s,"status":"published","value":"%s",\
                "metadata":{"utilizedDepth":"%s","excludedExchanges":[%s]}}
                """.formatted(time, value, depth, excluded);
    }

    private static String failed(String time, String reason) {
        return """
                {"index":"OBSI","calculationTime":%s,"status":"failed","reason":"%s"}
                """.formatted(time, reason);
    }

    private static final String T = "\"2026-10-27T12:00:00Z\"";

    static Stream<Arguments> publishedFixtures() {
        return Stream.of(
                Arguments.of("data/sample-input.json", published(T, "100.23", "4", "\"Gamma\",\"Delta\"")),
                Arguments.of("data/test-cases/single-exchange.json", published(T, "100.10", "1", "")),
                Arguments.of("data/test-cases/cross-exchange-uncrossing.json", published(T, "100.37", "5", "")),
                Arguments.of("data/test-cases/rounding-half-up.json", published(T, "100.13", "2", "")),
                Arguments.of("data/test-cases/screening-edge-cases.json", published("\"2026-10-27T12:00:00.000Z\"",
                        "100.06", "11", "\"Crossed\",\"Unreadable\",\"EmptyAfterScreen\",\"OverFivePercent\",\"StringNumbers\"")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedFixtures")
    void publishesToStandardOutputWithExitZero(String file, String expected) {
        Run run = run(file);

        assertEquals(new Run(0, expected, ""), run);
    }

    static Stream<Arguments> failedFixtures() {
        return Stream.of(
                Arguments.of("data/test-cases/fail-no-surviving-books.json",
                        failed(T, "no constituent book survived screening")),
                Arguments.of("data/test-cases/fail-insufficient-depth.json",
                        failed(T, "insufficient depth: both curves are defined at 1 sampled volume(s), "
                                + "at least 2 consecutive samples are needed")),
                Arguments.of("data/test-cases/fail-no-cutoff-crossing.json",
                        failed(T, "no mid-spread cutoff crossing before either curve ends")),
                Arguments.of("data/test-cases/fail-spread-too-wide.json",
                        failed(T, "no mid-spread cutoff crossing before either curve ends")),
                Arguments.of("data/test-cases/fail-missing-books.json",
                        failed(T, "missing required field: books")),
                Arguments.of("data/test-cases/fail-invalid-json.json",
                        failed("null", "input is not valid JSON")),
                Arguments.of("data/test-cases/fail-non-utc-time.json",
                        failed("null", "calculationTime is not in UTC: 2026-10-27T12:00:00+02:00")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedFixtures")
    void reportsFailuresOnStandardErrorWithExitOne(String file, String expected) {
        Run run = run(file);

        assertEquals(new Run(1, "", expected), run);
    }

    @Test
    void missingFileIsAFailureWithNullCalculationTime(@TempDir Path dir) {
        Path missing = dir.resolve("nope.json");

        assertEquals(new Run(1, "", failed("null", "cannot read input file: " + missing)), run(missing.toString()));
    }

    @Test
    void readsAnAbsolutePath(@TempDir Path dir) throws Exception {
        Path copy = Files.copy(Path.of("data/sample-input.json"), dir.resolve("snapshot.json"));

        assertEquals(0, run(copy.toAbsolutePath().toString()).exitCode());
    }

    @Test
    void noArgumentsIsAUsageError() {
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run());
    }

    @Test
    void tooManyArgumentsIsAUsageError() {
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run("a.json", "b.json"));
    }

    @Test
    void unknownFlagIsAUsageError() {
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run("--verbose"));
    }

    static Stream<Arguments> newPedFixtures() {
        String saturday = "\"2026-10-31T12:00:00Z\"";
        return Stream.of(
                // Proposal example: unlock 18h before a Tuesday -> 1%; same result as baseline.
                Arguments.of("data/new-ped.json", published(T, "100.23", "4", "\"Gamma\",\"Delta\"")),
                Arguments.of("data/test-cases/new-ped-weekday.json", published(T, "100.00", "15", "\"Zeta\"")),
                Arguments.of("data/test-cases/new-ped-weekend.json", published(saturday, "100.01", "15", "")),
                Arguments.of("data/test-cases/new-ped-unlock-on-weekend.json",
                        published(saturday, "100.00", "15", "\"Epsilon\",\"Zeta\"")),
                Arguments.of("data/test-cases/new-ped-unlock-boundary.json",
                        published(T, "100.00", "15", "\"Epsilon\",\"Zeta\"")),
                Arguments.of("data/test-cases/new-ped-unlock-expired.json", published(T, "100.00", "15", "\"Zeta\"")),
                Arguments.of("data/test-cases/new-ped-unlock-not-started.json", published(saturday, "100.01", "15", "")),
                // Baseline fixtures carry no unlockEvents: weekday regime (2%) changes nothing for the sample.
                Arguments.of("data/sample-input.json", published(T, "100.23", "4", "\"Gamma\",\"Delta\"")));
    }

    @ParameterizedTest(name = "--new-ped {0}")
    @MethodSource("newPedFixtures")
    void newPedPublishesWithTheRegimeLimit(String file, String expected) {
        assertEquals(new Run(0, expected, ""), run("--new-ped", file));
    }

    @Test
    void newPedRejectsMalformedUnlockEvents() {
        assertEquals(new Run(1, "", failed(T, "unlockEvents[0] is not in UTC: 2026-10-26T18:00:00+01:00")),
                run("--new-ped", "data/test-cases/fail-new-ped-invalid-unlock.json"));
    }

    @Test
    void baselineIgnoresUnlockEventsAndKeepsTheStaticLimit() {
        // Same books as --new-ped above: baseline 5% excludes nothing, even inside an unlock window.
        assertEquals(new Run(0, published("\"2026-10-31T12:00:00Z\"", "100.01", "15", ""), ""),
                run("data/test-cases/new-ped-unlock-on-weekend.json"));
        assertEquals(new Run(0, published(T, "100.01", "15", ""), ""),
                run("data/test-cases/fail-new-ped-invalid-unlock.json"));
    }

    @Test
    void newPedWithoutAFileIsAUsageError() {
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run("--new-ped"));
    }

    @Test
    void newPedFlagMustComeFirstAndOnlyOnce() {
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run("data/new-ped.json", "--new-ped"));
        assertEquals(new Run(2, "", failed("null", Cli.USAGE)), run("--new-ped", "--new-ped"));
    }
}
