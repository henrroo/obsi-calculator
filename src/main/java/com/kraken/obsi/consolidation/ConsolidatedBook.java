package com.kraken.obsi.consolidation;

import com.kraken.obsi.model.PriceLevel;

import java.util.List;

/**
 * The single order book built from all surviving exchanges, after uncrossing (methodology
 * section 3). Both sides are non-empty, each price appears once per side, and the best bid is
 * strictly below the best ask.
 *
 * @param bids levels ordered from highest to lowest price
 * @param asks levels ordered from lowest to highest price
 */
public record ConsolidatedBook(List<PriceLevel> bids, List<PriceLevel> asks) {

    public ConsolidatedBook {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
