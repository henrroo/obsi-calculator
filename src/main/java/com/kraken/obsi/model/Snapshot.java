package com.kraken.obsi.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One captured input snapshot: the calculation time and the constituent books in input order.
 *
 * @param unlockEvents announced Token XYZ unlock start times. Only read for the {@code --new-ped}
 *                     calculation (proposal.md, Approach A); always empty in the baseline methodology.
 */
public record Snapshot(CalculationTime calculationTime, List<Book> books, List<Instant> unlockEvents) {

    public Snapshot {
        Objects.requireNonNull(calculationTime, "calculationTime");
        books = List.copyOf(books);
        unlockEvents = List.copyOf(unlockEvents);
    }

    /** Snapshot for the baseline methodology, which has no unlock events. */
    public Snapshot(CalculationTime calculationTime, List<Book> books) {
        this(calculationTime, books, List.of());
    }
}
