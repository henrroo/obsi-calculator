package com.kraken.obsi.screening;

import com.kraken.obsi.model.Snapshot;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic regime-based PED limit: the {@code --new-ped} extension, proposal.md Approach A.
 *
 * <p>The regime is a pure function of the snapshot's {@code calculationTime} and its announced
 * {@code unlockEvents}, all in UTC:
 *
 * <table>
 *   <caption>Regimes, in order of precedence</caption>
 *   <tr><th>Regime</th><th>Condition</th><th>Limit</th></tr>
 *   <tr><td>{@link Regime#UNLOCK_WINDOW}</td>
 *       <td>0 ≤ calculationTime − unlockStart ≤ 48h, for any unlock</td><td>1%</td></tr>
 *   <tr><td>{@link Regime#WEEKDAY}</td><td>Monday 00:00 to Friday 23:59:59.999…</td><td>2%</td></tr>
 *   <tr><td>{@link Regime#WEEKEND}</td><td>Saturday 00:00 to Sunday 23:59:59.999…</td><td>5%</td></tr>
 * </table>
 *
 * Both ends of the unlock window are inclusive, as the proposal states. An unlock announced for a
 * time after {@code calculationTime} has not started yet and does not apply.
 *
 * @param unlockLimit   limit inside an unlock window
 * @param weekdayLimit  limit Monday to Friday outside unlock windows
 * @param weekendLimit  limit Saturday and Sunday outside unlock windows
 * @param unlockWindow  how long after an unlock start the unlock limit applies
 */
public record RegimePedLimit(BigDecimal unlockLimit, BigDecimal weekdayLimit, BigDecimal weekendLimit,
                             Duration unlockWindow) implements PedLimitPolicy {

    /** The regimes and limits selected in proposal.md (Approach A). */
    public static final RegimePedLimit PROPOSAL_A = new RegimePedLimit(
            new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.05"), Duration.ofHours(48));

    public enum Regime { UNLOCK_WINDOW, WEEKDAY, WEEKEND }

    public RegimePedLimit {
        requirePositive(unlockLimit, "unlockLimit");
        requirePositive(weekdayLimit, "weekdayLimit");
        requirePositive(weekendLimit, "weekendLimit");
        Objects.requireNonNull(unlockWindow, "unlockWindow");
        if (unlockWindow.isNegative()) {
            throw new IllegalArgumentException("unlockWindow must not be negative: " + unlockWindow);
        }
    }

    @Override
    public BigDecimal limitFor(Snapshot snapshot) {
        return limitFor(regimeAt(snapshot.calculationTime().instant(), snapshot.unlockEvents()));
    }

    public BigDecimal limitFor(Regime regime) {
        return switch (regime) {
            case UNLOCK_WINDOW -> unlockLimit;
            case WEEKDAY -> weekdayLimit;
            case WEEKEND -> weekendLimit;
        };
    }

    /** The regime in force at {@code time}. An unlock window takes precedence over the day of week. */
    public Regime regimeAt(Instant time, List<Instant> unlockStarts) {
        if (unlockStarts.stream().anyMatch(start -> isWithinUnlockWindow(time, start))) {
            return Regime.UNLOCK_WINDOW;
        }
        return switch (time.atOffset(ZoneOffset.UTC).getDayOfWeek()) {
            case SATURDAY, SUNDAY -> Regime.WEEKEND;
            case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> Regime.WEEKDAY;
        };
    }

    /** 0 ≤ time − unlockStart ≤ unlockWindow. */
    boolean isWithinUnlockWindow(Instant time, Instant unlockStart) {
        Duration sinceStart = Duration.between(unlockStart, time);
        return !sinceStart.isNegative() && sinceStart.compareTo(unlockWindow) <= 0;
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be strictly positive: " + value);
        }
    }
}
