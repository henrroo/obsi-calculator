package com.kraken.obsi.screening;

import com.kraken.obsi.model.Snapshot;

import java.math.BigDecimal;

/**
 * Decides the orderbook percentage deviation (PED) limit that applies to one snapshot.
 *
 * <ul>
 *   <li>{@link StaticPedLimit}: the baseline methodology, one fixed limit (5%).</li>
 *   <li>{@link RegimePedLimit}: the {@code --new-ped} extension (proposal.md, Approach A), a limit
 *       chosen from the snapshot's {@code calculationTime} and announced unlock events.</li>
 * </ul>
 */
public sealed interface PedLimitPolicy permits StaticPedLimit, RegimePedLimit {

    /** PED limit for this snapshot, as a fraction (5% = 0.05). */
    BigDecimal limitFor(Snapshot snapshot);
}
