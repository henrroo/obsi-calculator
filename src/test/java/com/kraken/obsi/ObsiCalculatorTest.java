package com.kraken.obsi;

import com.kraken.obsi.json.SnapshotParser;
import com.kraken.obsi.model.IndexResult;
import com.kraken.obsi.model.Snapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ObsiCalculatorTest {

    private final SnapshotParser parser = new SnapshotParser();
    private final ObsiCalculator calculator = new ObsiCalculator();

    private Snapshot sample() throws Exception {
        return parser.parse(Path.of("data/sample-input.json"));
    }

    @Test
    void publishesTheSampleSnapshot() throws Exception {
        IndexResult result = calculator.calculate(sample());

        assertEquals(new IndexResult.Published(
                        "OBSI", "2026-10-27T12:00:00Z", "published", "100.23",
                        new IndexResult.Metadata("4", List.of("Gamma", "Delta"))),
                result);
    }

    @Test
    void failsWhenNoBookSurvivesScreening() throws Exception {
        Snapshot snapshot = parser.parse("""
                {"calculationTime": "2026-10-27T12:00:00Z",
                 "books": [{"exchange": "Crossed", "bids": [[101, 1]], "asks": [[100, 1]]}]}
                """);

        assertEquals(new IndexResult.Failed(
                        "OBSI", "2026-10-27T12:00:00Z", "failed", "no constituent book survived screening"),
                calculator.calculate(snapshot));
    }

    @Test
    void failsWhenThereAreNoBooksAtAll() throws Exception {
        Snapshot snapshot = parser.parse("""
                {"calculationTime": "2026-10-27T12:00:00Z", "books": []}
                """);

        IndexResult.Failed failed = assertInstanceOf(IndexResult.Failed.class, calculator.calculate(snapshot));
        assertEquals("no constituent book survived screening", failed.reason());
    }

    @Test
    void failsWhenTheUtilizedDepthIsUndefined() throws Exception {
        Snapshot snapshot = parser.parse("""
                {"calculationTime": "2026-10-27T12:00:00.5Z",
                 "books": [{"exchange": "Wide", "bids": [[99, 5]], "asks": [[101, 5]]}]}
                """);

        assertEquals(new IndexResult.Failed(
                        "OBSI", "2026-10-27T12:00:00.5Z", "failed", "no mid-spread cutoff crossing before either curve ends"),
                calculator.calculate(snapshot));
    }

    @Test
    void uncrossingAcrossExchangesFeedsTheIndex() throws Exception {
        // A's best bid 100.4 crosses B's best ask 100.3 (0.6 XYZ) once the books are combined.
        Snapshot snapshot = parser.parse(Path.of("data/test-cases/cross-exchange-uncrossing.json"));

        IndexResult.Published published = assertInstanceOf(IndexResult.Published.class, calculator.calculate(snapshot));
        assertEquals("100.37", published.value());
        assertEquals("5", published.metadata().utilizedDepth());
        assertEquals(List.of(), published.metadata().excludedExchanges());
    }

    @Test
    void newPedAppliesTheRegimeLimitToTheSameBooks() throws Exception {
        // Epsilon is 1.5% and Zeta 3% from the median of 100; only the time and unlocks differ.
        SnapshotParser newPedParser = SnapshotParser.withUnlockEvents();
        ObsiCalculator newPed = ObsiCalculator.newPed();

        assertEquals(List.of("Zeta"), excluded(newPed, newPedParser, "new-ped-weekday.json"));                  // 2%
        assertEquals(List.of(), excluded(newPed, newPedParser, "new-ped-weekend.json"));                        // 5%
        assertEquals(List.of("Epsilon", "Zeta"), excluded(newPed, newPedParser, "new-ped-unlock-on-weekend.json")); // 1%
    }

    @Test
    void baselineIgnoresTheRegimeAndKeepsFivePercent() throws Exception {
        SnapshotParser newPedParser = SnapshotParser.withUnlockEvents();

        assertEquals(List.of(), excluded(new ObsiCalculator(), newPedParser, "new-ped-unlock-on-weekend.json"));
    }

    @Test
    void newPedOnTheProposalExampleMatchesBaselineBecauseNoBookIsNearTheLimit() throws Exception {
        Snapshot snapshot = SnapshotParser.withUnlockEvents().parse(Path.of("data/new-ped.json"));

        // Unlock regime (1%): Alpha and Beta are 0.05% from the median; Gamma is crossed, Delta far off.
        assertEquals(new ObsiCalculator().calculate(sample()), ObsiCalculator.newPed().calculate(snapshot));
    }

    private static List<String> excluded(ObsiCalculator calculator, SnapshotParser parser, String fixture)
            throws Exception {
        Snapshot snapshot = parser.parse(Path.of("data/test-cases/" + fixture));
        return assertInstanceOf(IndexResult.Published.class, calculator.calculate(snapshot)).metadata().excludedExchanges();
    }

    @Test
    void parametersReachTheScreener() throws Exception {
        // With a 60% PED limit Delta (49.6% from the median) is no longer excluded.
        MethodologyParameters widePed = new MethodologyParameters(
                new BigDecimal("0.6"), new BigDecimal("0.005"), BigDecimal.ONE, new BigDecimal("0.3"), 2, RoundingMode.HALF_UP);

        IndexResult.Published published =
                assertInstanceOf(IndexResult.Published.class, new ObsiCalculator(widePed).calculate(sample()));
        assertEquals(List.of("Gamma"), published.metadata().excludedExchanges());
    }
}
