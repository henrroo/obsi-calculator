package com.kraken.obsi.model;

/**
 * A {@code books} element whose structure could not be read (not an object, missing or mistyped
 * {@code exchange}, {@code bids} or {@code asks}).
 *
 * @param exchange the exchange name if it could still be recovered, otherwise {@code null}
 */
public record UnreadableBook(String exchange) implements Book {
}
