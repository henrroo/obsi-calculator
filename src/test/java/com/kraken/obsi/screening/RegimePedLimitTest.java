package com.kraken.obsi.screening;

import com.kraken.obsi.model.CalculationTime;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.screening.RegimePedLimit.Regime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegimePedLimitTest {

    private final RegimePedLimit policy = RegimePedLimit.PROPOSAL_A;

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    private Regime regime(String time, String... unlocks) {
        return policy.regimeAt(t(time), List.of(unlocks).stream().map(Instant::parse).toList());
    }

    @Nested
    @DisplayName("proposal.md limits")
    class Limits {

        @Test
        void proposalALimitsMatchTheProposal() {
            assertEquals(new BigDecimal("0.01"), policy.limitFor(Regime.UNLOCK_WINDOW));
            assertEquals(new BigDecimal("0.02"), policy.limitFor(Regime.WEEKDAY));
            assertEquals(new BigDecimal("0.05"), policy.limitFor(Regime.WEEKEND));
            assertEquals(Duration.ofHours(48), policy.unlockWindow());
        }

        @Test
        void limitForSnapshotUsesItsCalculationTimeAndUnlockEvents() {
            CalculationTime tuesday = CalculationTime.parse("2026-10-27T12:00:00Z");

            assertEquals(new BigDecimal("0.02"), policy.limitFor(new Snapshot(tuesday, List.of())));
            assertEquals(new BigDecimal("0.01"),
                    policy.limitFor(new Snapshot(tuesday, List.of(), List.of(t("2026-10-26T18:00:00Z")))));
        }

        @Test
        void rejectsInvalidConfiguration() {
            BigDecimal p = new BigDecimal("0.01");
            Duration h48 = Duration.ofHours(48);
            assertThrows(IllegalArgumentException.class, () -> new RegimePedLimit(BigDecimal.ZERO, p, p, h48));
            assertThrows(IllegalArgumentException.class, () -> new RegimePedLimit(p, new BigDecimal("-0.02"), p, h48));
            assertThrows(IllegalArgumentException.class, () -> new RegimePedLimit(p, p, BigDecimal.ZERO, h48));
            assertThrows(IllegalArgumentException.class, () -> new RegimePedLimit(p, p, p, Duration.ofHours(-1)));
            assertThrows(NullPointerException.class, () -> new RegimePedLimit(p, p, p, null));
        }
    }

    @Nested
    @DisplayName("day of week (no unlock)")
    class DayOfWeek {

        @ParameterizedTest(name = "{0} ({1}) -> {2}")
        @CsvSource({
                "2026-10-26T12:00:00Z, Monday,    WEEKDAY",
                "2026-10-27T12:00:00Z, Tuesday,   WEEKDAY",
                "2026-10-28T12:00:00Z, Wednesday, WEEKDAY",
                "2026-10-29T12:00:00Z, Thursday,  WEEKDAY",
                "2026-10-30T12:00:00Z, Friday,    WEEKDAY",
                "2026-10-31T12:00:00Z, Saturday,  WEEKEND",
                "2026-11-01T12:00:00Z, Sunday,    WEEKEND"
        })
        void everyDayMapsToItsRegime(String time, String day, Regime expected) {
            assertEquals(expected, regime(time));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "2026-10-30T23:59:59.999999999Z, WEEKDAY",  // last instant of Friday
                "2026-10-31T00:00:00Z,           WEEKEND",  // first instant of Saturday
                "2026-11-01T23:59:59.999999999Z, WEEKEND",  // last instant of Sunday
                "2026-11-02T00:00:00Z,           WEEKDAY"   // first instant of Monday
        })
        void weekendBoundariesAreMidnightUtc(String time, Regime expected) {
            assertEquals(expected, regime(time));
        }

        @Test
        void dayIsTakenInUtcNotLocalTime() {
            // Saturday 01:00 UTC is still Friday evening in New York; UTC decides.
            assertEquals(Regime.WEEKEND, regime("2026-10-31T01:00:00Z"));
        }
    }

    @Nested
    @DisplayName("unlock window: 0 <= calculationTime - unlockStart <= 48h")
    class UnlockWindow {

        @ParameterizedTest(name = "unlock {1} for calc {0} -> {2}")
        @CsvSource({
                "2026-10-27T12:00:00Z, 2026-10-27T12:00:00Z,           UNLOCK_WINDOW",  // 0: start is inclusive
                "2026-10-27T12:00:00Z, 2026-10-26T18:00:00Z,           UNLOCK_WINDOW",  // 18h, proposal example
                "2026-10-27T12:00:00Z, 2026-10-25T12:00:00Z,           UNLOCK_WINDOW",  // exactly 48h: inclusive
                "2026-10-27T12:00:00Z, 2026-10-25T11:59:59.999999999Z, WEEKDAY",        // 48h + 1ns: expired
                "2026-10-27T12:00:00Z, 2026-10-27T12:00:00.000000001Z, WEEKDAY"         // not started yet
        })
        void windowIsInclusiveAtBothEnds(String time, String unlock, Regime expected) {
            assertEquals(expected, regime(time, unlock));
        }

        @Test
        void unlockTakesPrecedenceOverTheWeekend() {
            assertEquals(Regime.UNLOCK_WINDOW, regime("2026-10-31T12:00:00Z", "2026-10-30T12:00:00Z"));
            assertEquals(new BigDecimal("0.01"), policy.limitFor(Regime.UNLOCK_WINDOW));
        }

        @Test
        void windowCanSpanFromFridayIntoTheWeekend() {
            // Unlock Friday 20:00 -> window runs to Sunday 20:00.
            assertEquals(Regime.UNLOCK_WINDOW, regime("2026-11-01T20:00:00Z", "2026-10-30T20:00:00Z"));
            assertEquals(Regime.WEEKEND, regime("2026-11-01T20:00:01Z", "2026-10-30T20:00:00Z"));
        }

        @Test
        void anyOfSeveralUnlocksOpensTheWindow() {
            assertEquals(Regime.UNLOCK_WINDOW,
                    regime("2026-10-27T12:00:00Z", "2026-09-01T00:00:00Z", "2026-10-27T00:00:00Z", "2026-12-01T00:00:00Z"));
        }

        @Test
        void unlocksOutsideTheWindowFallBackToTheDayOfWeek() {
            assertEquals(Regime.WEEKDAY, regime("2026-10-27T12:00:00Z", "2026-09-01T00:00:00Z", "2026-12-01T00:00:00Z"));
            assertEquals(Regime.WEEKEND, regime("2026-10-31T12:00:00Z", "2026-10-31T12:00:01Z"));
        }

        @Test
        void noUnlocksMeansDayOfWeekOnly() {
            assertEquals(Regime.WEEKDAY, regime("2026-10-27T12:00:00Z"));
        }
    }

    @Nested
    @DisplayName("static policy (baseline)")
    class Static {

        @Test
        void returnsTheSameLimitForEverySnapshot() {
            StaticPedLimit baseline = new StaticPedLimit(new BigDecimal("0.05"));
            CalculationTime saturday = CalculationTime.parse("2026-10-31T12:00:00Z");

            assertEquals(new BigDecimal("0.05"), baseline.limitFor(new Snapshot(saturday, List.of())));
            assertEquals(new BigDecimal("0.05"),
                    baseline.limitFor(new Snapshot(saturday, List.of(), List.of(t("2026-10-31T00:00:00Z")))));
        }

        @Test
        void rejectsNonPositiveLimit() {
            assertThrows(IllegalArgumentException.class, () -> new StaticPedLimit(BigDecimal.ZERO));
        }
    }
}
