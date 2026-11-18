package com.kraken.obsi.consolidation;

/**
 * Uncrossing the consolidated book left the bid side, the ask side, or both without any level.
 * Per methodology section 3 the calculation fails and no index value is published.
 */
public final class UncrossingExhaustedException extends Exception {

    public UncrossingExhaustedException(String message) {
        super(message);
    }
}
