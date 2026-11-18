package com.kraken.obsi.model;

/**
 * A constituent entry of the snapshot's {@code books} array, in input order.
 *
 * <p>A book whose structure cannot be read is not an input error: the methodology excludes that
 * book and carries on, so it is modelled explicitly rather than rejected by the parser.
 */
public sealed interface Book permits ExchangeBook, UnreadableBook {

    /** Exchange name; may be {@code null} only for an {@link UnreadableBook}. */
    String exchange();
}
