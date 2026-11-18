# OBSI Calculator

Handoff notes for the next Index Engineer picking up the Order Book Snapshot Index (OBSI).

## What this project is for

OBSI publishes a USD price for token XYZ at a given time T. It takes the order books captured from the constituent exchanges at T and produces that price in four steps:

1. Drop exchanges with broken books or prices out of line with the rest.
2. Combine the remaining books into one.
3. Measure how deep that combined book stays tight around its mid-price.
4. Take a depth-weighted average of the mid-price up to that depth.

This repository is a Java 25 command-line tool that runs that calculation for one snapshot file and prints the result as JSON. It implements [obsi-methodology.md](obsi-methodology.md), the specification from the Product Manager. The `--new-ped` flag runs a proposed variant of the exchange-deviation (PED) screen, described in [proposal.md](proposal.md).

This README is your entry point: how to run the tool, where each methodology step lives in the code, the choices and assumptions made, and what is still missing before production.

## Quick start

Requires Java 25. Maven comes from the wrapper.

```bash
./build.sh                               # compile, run all tests, build target/obsi-calculator.jar
./run.sh data/sample-input.json          # baseline methodology
./run.sh --new-ped data/new-ped.json     # regime-based PED (proposal.md, Approach A)
```

| Outcome | Stream | Exit |
|---|---|---|
| Published | stdout, one JSON line | 0 |
| Calculation or input failure | stderr, one JSON line | 1 |
| Bad arguments | stderr, one JSON line | 2 |

Nothing else is printed, so both streams stay machine-readable. The sample publishes `100.23`, utilized depth `4`, and excludes `Gamma` (crossed book) and `Delta` (PED 49.6%).

## Pipeline

Every stage is pure and stateless, and each lives in its own package.

| Methodology | Class | Notes |
|---|---|---|
| Input contract | `json.SnapshotParser` | Jackson tree model; numbers read as exact `BigDecimal` |
| §2 Screens | `screening.OrderBookScreener` | Entry screen, structure, median mid, PED |
| §3 Consolidation | `consolidation.BookConsolidator` | Sum by price, sort, uncross |
| §4 Curves | `curve.PriceVolumeCurves` | Eqs. 1a–1f |
| §5 Depth and value | `index.IndexCalculator` | Eq. 2, Eq. 3, `HALF_UP` to 2 dp |
| §6 Failures | `ObsiCalculator` | Every failure becomes the failure JSON |
| §7 Parameters | `MethodologyParameters` | One record holds all fixed values |
| CLI | `Cli`, `Main` | Argument handling and output streams |

## Numerical decisions

- **No `double` anywhere.** Prices, sizes, mids, PED and curves are exact `BigDecimal`.
- **Threshold tests avoid division.** PED is tested as `|m − median| > 0.05·median`, and `midSV ≤ D` as `ask ≤ 1.005·mid`. Values exactly at 5% and at 0.5% therefore count as inside, as the spec requires.
- **Own `exp` implementation.** The JDK has no `BigDecimal.exp`, so `index.BigDecimalMath` sums a Taylor series with range reduction at 40 significant digits.
- **Eq. 3 is rearranged.** It is evaluated as `midPV(1) + Σ(midPV(v) − midPV(1))·w/NF`, which is the same value mathematically. A flat curve at e.g. 100.125 then stays exact and rounds `HALF_UP` to 100.13, instead of drifting to 100.1249… and publishing 100.12.

## Assumptions and interpretations

- `calculationTime` must be ISO-8601 with `Z` or `+00:00`. It is echoed verbatim; any other offset is an input error, reported with `calculationTime: null`.
- A `[price, size]` entry must be two JSON numbers. Numeric strings such as `"100.0"` are discarded like non-positive values. A book that is not an object, or whose `exchange`, `bids` or `asks` are missing or mistyped, is excluded rather than failing the run.
- A book excluded without a readable `exchange` name cannot be listed in `excludedExchanges`.
- Identical prices means numerically equal: `100.0`, `100.00` and `1E+2` are one level.
- The median of mid-prices is computed once over the structurally valid books and is not recomputed after PED exclusions.
- After screening, uncrossing can never exhaust a side, because every surviving book has bid < ask. The check is kept as a guard and unit-tested with unscreened books.
- Unknown input fields are ignored.

## `--new-ped` (Approach A)

`screening.RegimePedLimit` picks the PED limit from `calculationTime` in UTC:

| Regime | Condition | Limit |
|---|---|---|
| Unlock window (takes precedence) | `0 ≤ calculationTime − unlockStart ≤ 48h` | 1% |
| Weekday | Monday to Friday | 2% |
| Weekend | Saturday and Sunday | 5% |

Unlock starts come from the optional top-level `unlockEvents` array of UTC strings. A malformed entry fails the run, because silently dropping an unlock would apply a looser limit. Baseline mode ignores the field. Only the PED limit differs between the two modes; every later stage is shared.

## Tests

`./mvnw test` runs 241 deterministic JUnit 5 tests. Tests exist per stage (boundaries, exact-arithmetic cases, every failure path) and end to end in `CliTest`, which checks the exact stdout, stderr and exit code for every file in `data/`.

`data/test-cases/` holds files for manual runs: screening edge cases, cross-exchange uncrossing, the `HALF_UP` tie, each failure reason, and the PED regimes on identical books. Expected values were cross-checked against an independent Python `decimal` implementation written from the methodology text. That implementation is not part of this repository.

## Libraries

- **Jackson databind:** JSON parsing and serialization.
- **JUnit 5:** tests.
- **maven-shade-plugin:** builds the runnable fat JAR.

## Production gaps

- **Performance:** Eq. 2 and Eq. 3 loop once per sampled XYZ. That is fine for realistic depth, but sizes in the millions would be slow; walking level boundaries would remove this.
- **Duplicate exchanges:** exchange names are assumed unique but this is not enforced, so duplicates are treated as separate books.
- **Regime configuration:** the limits and the 48h window are code constants. Production needs governed configuration with an effective date.
- **Unlock calendar:** unlock events arrive with each snapshot. They should come from an audited calendar.
- **Holidays:** the regimes do not account for public holidays.
- **Auditability:** the output does not say which PED regime or limit applied. Adding `metadata.pedRegime` would help audits, but would change the output contract.
- **Persistent exchange premiums:** Approach A does not address them (see proposal.md).
- **Operations:** there is no logging, metrics, live feed or publication pipeline. These are out of scope for this exercise.
