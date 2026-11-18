package com.kraken.obsi.model;

import java.util.List;
import java.util.Objects;

/**
 * A structurally readable exchange order book. Levels are kept in input order and contain only
 * entries that passed the entry screen; either side may therefore be empty.
 */
public record ExchangeBook(String exchange, List<PriceLevel> bids, List<PriceLevel> asks) implements Book {

    public ExchangeBook {
        Objects.requireNonNull(exchange, "exchange");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
