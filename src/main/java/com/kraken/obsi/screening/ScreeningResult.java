package com.kraken.obsi.screening;

import com.kraken.obsi.model.ExchangeBook;

import java.util.List;

/**
 * Outcome of the constituent order book screens (methodology section 2).
 *
 * @param survivingBooks    books that passed every screen, in input order, holding only the
 *                          entries that passed the entry screen
 * @param excludedExchanges names of the screened-out exchanges, in input order
 */
public record ScreeningResult(List<ExchangeBook> survivingBooks, List<String> excludedExchanges) {

    public ScreeningResult {
        survivingBooks = List.copyOf(survivingBooks);
        excludedExchanges = List.copyOf(excludedExchanges);
    }
}
