package com.kraken.obsi.json;

import com.kraken.obsi.model.CalculationTime;

import java.util.Optional;

/**
 * The input is malformed at the top level and no calculation can be attempted.
 *
 * <p>Carries the calculation time when it was readable before the error was found, so the failure
 * output can still echo it.
 */
public final class SnapshotParseException extends Exception {

    private final transient CalculationTime calculationTime;

    public SnapshotParseException(String message, CalculationTime calculationTime, Throwable cause) {
        super(message, cause);
        this.calculationTime = calculationTime;
    }

    public SnapshotParseException(String message, CalculationTime calculationTime) {
        this(message, calculationTime, null);
    }

    public Optional<CalculationTime> calculationTime() {
        return Optional.ofNullable(calculationTime);
    }
}
