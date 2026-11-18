package com.kraken.obsi;

import com.kraken.obsi.json.SnapshotParseException;
import com.kraken.obsi.json.SnapshotParser;
import com.kraken.obsi.model.CalculationTime;
import com.kraken.obsi.model.IndexResult;
import com.kraken.obsi.model.Snapshot;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line contract: {@code run.sh [--new-ped] <snapshot.json>}.
 *
 * <p>Without a flag the baseline methodology runs. With {@code --new-ped} the PED limit comes from
 * proposal.md Approach A and the optional {@code unlockEvents} input field is read.
 *
 * <p>Exactly one JSON object is printed per run: a published result on standard output with exit
 * status 0, or a failure on standard error with a nonzero exit status. Nothing else is written to
 * either stream, so the output stays machine-readable.
 */
public final class Cli {

    static final int EXIT_PUBLISHED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    static final String NEW_PED_FLAG = "--new-ped";
    static final String USAGE = "usage: run.sh [" + NEW_PED_FLAG + "] <snapshot.json>";

    private final SnapshotParser baselineParser = new SnapshotParser();
    private final SnapshotParser newPedParser = SnapshotParser.withUnlockEvents();
    private final ObsiCalculator baselineCalculator = new ObsiCalculator();
    private final ObsiCalculator newPedCalculator = ObsiCalculator.newPed();

    public int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() == 1 && !args.getFirst().startsWith("--")) {
            return calculate(Path.of(args.getFirst()), baselineParser, baselineCalculator, out, err);
        }
        if (args.size() == 2 && args.getFirst().equals(NEW_PED_FLAG) && !args.getLast().startsWith("--")) {
            return calculate(Path.of(args.getLast()), newPedParser, newPedCalculator, out, err);
        }
        return fail(err, null, USAGE, EXIT_USAGE);
    }

    private int calculate(Path input, SnapshotParser parser, ObsiCalculator calculator,
                          PrintStream out, PrintStream err) {
        CalculationTime calculationTime = null;
        try {
            Snapshot snapshot = parser.parse(input);
            calculationTime = snapshot.calculationTime();
            return switch (calculator.calculate(snapshot)) {
                case IndexResult.Published published -> {
                    out.println(baselineParser.serialize(published));
                    out.flush();
                    yield EXIT_PUBLISHED;
                }
                case IndexResult.Failed failed -> print(err, failed, EXIT_FAILED);
            };
        } catch (SnapshotParseException e) {
            return fail(err, e.calculationTime().orElse(null), e.getMessage(), EXIT_FAILED);
        } catch (RuntimeException e) {
            // A bug, not an input problem. Still answer with the failure contract rather than a
            // stack trace, so whatever reads standard error gets valid JSON.
            return fail(err, calculationTime, "internal error: " + e, EXIT_FAILED);
        }
    }

    private int fail(PrintStream err, CalculationTime calculationTime, String reason, int exitCode) {
        return print(err, IndexResult.failed(calculationTime, reason), exitCode);
    }

    private int print(PrintStream err, IndexResult.Failed failed, int exitCode) {
        err.println(baselineParser.serialize(failed));
        err.flush();
        return exitCode;
    }
}
