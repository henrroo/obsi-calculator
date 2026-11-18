package com.kraken.obsi;

import com.kraken.obsi.consolidation.BookConsolidator;
import com.kraken.obsi.consolidation.ConsolidatedBook;
import com.kraken.obsi.consolidation.UncrossingExhaustedException;
import com.kraken.obsi.index.IndexCalculator;
import com.kraken.obsi.index.IndexValue;
import com.kraken.obsi.index.UtilizedDepthException;
import com.kraken.obsi.model.IndexResult;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.screening.OrderBookScreener;
import com.kraken.obsi.screening.PedLimitPolicy;
import com.kraken.obsi.screening.RegimePedLimit;
import com.kraken.obsi.screening.ScreeningResult;
import com.kraken.obsi.screening.StaticPedLimit;

import java.util.Objects;

/**
 * The full OBSI calculation for one parsed snapshot: screening (section 2), consolidation and
 * uncrossing (section 3), curves, utilized depth and index value (sections 4 and 5). Every
 * calculation failure listed in section 6 becomes a {@link IndexResult.Failed}; nothing is thrown.
 *
 * <p>The only difference between the baseline and the {@code --new-ped} calculation is the
 * {@link PedLimitPolicy} that picks the PED limit for the screen; every later stage is shared.
 */
public final class ObsiCalculator {

    private final PedLimitPolicy pedLimitPolicy;
    private final BookConsolidator consolidator;
    private final IndexCalculator indexCalculator;

    /** Baseline methodology with the fixed section 7 parameters. */
    public ObsiCalculator() {
        this(MethodologyParameters.DEFAULT);
    }

    /** Baseline methodology: a static PED limit taken from {@code parameters}. */
    public ObsiCalculator(MethodologyParameters parameters) {
        this(parameters, new StaticPedLimit(parameters.pedLimit()));
    }

    public ObsiCalculator(MethodologyParameters parameters, PedLimitPolicy pedLimitPolicy) {
        this.pedLimitPolicy = Objects.requireNonNull(pedLimitPolicy, "pedLimitPolicy");
        this.consolidator = new BookConsolidator();
        this.indexCalculator = new IndexCalculator(parameters);
    }

    /** The {@code --new-ped} calculation: proposal.md Approach A, otherwise the section 7 parameters. */
    public static ObsiCalculator newPed() {
        return new ObsiCalculator(MethodologyParameters.DEFAULT, RegimePedLimit.PROPOSAL_A);
    }

    public IndexResult calculate(Snapshot snapshot) {
        OrderBookScreener screener = new OrderBookScreener(pedLimitPolicy.limitFor(snapshot));
        ScreeningResult screening = screener.screen(snapshot);
        if (screening.survivingBooks().isEmpty()) {
            return IndexResult.failed(snapshot.calculationTime(), "no constituent book survived screening");
        }
        try {
            ConsolidatedBook book = consolidator.consolidate(screening.survivingBooks());
            IndexValue index = indexCalculator.calculate(book);
            return IndexResult.published(
                    snapshot.calculationTime(), index.publishedValue(), index.utilizedDepth(), screening.excludedExchanges());
        } catch (UncrossingExhaustedException | UtilizedDepthException e) {
            return IndexResult.failed(snapshot.calculationTime(), e.getMessage());
        }
    }
}
