package com.picsou.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared deduplication helper for sync services (TR, Bourso) when multiple
 * external positions (e.g. several ISINs) resolve to the same ticker.
 *
 * <p>The merge uses a VWAP (Volume Weighted Average Price) formula so the
 * resulting {@code averageBuyIn} is the quantity-weighted mean of the inputs,
 * rather than the non-deterministic "first-seen" value that {@code HashMap}
 * iteration order would yield.
 */
public final class HoldingDedup {

    private HoldingDedup() {}

    public record HoldingAgg(
        BigDecimal quantity,
        BigDecimal averageBuyIn,
        BigDecimal currentPrice,
        String name
    ) {}

    /**
     * Merges two aggregates of the same ticker. The resulting average buy-in is:
     * <pre>
     *   weightedAvg = (q1 * a1 + q2 * a2) / (q1 + q2)
     * </pre>
     * Scale 8, {@link RoundingMode#HALF_UP} -- matches the project-wide
     * convention used by {@code HoldingComputeService}.
     *
     * <p>Null averages are treated as zero; if total quantity is zero, the
     * previous aggregate is returned unchanged (cannot divide).
     */
    public static HoldingAgg vwapMerge(HoldingAgg prev, HoldingAgg next) {
        BigDecimal totalQty = prev.quantity().add(next.quantity());
        if (totalQty.signum() == 0) {
            return prev;
        }
        BigDecimal prevAvg = prev.averageBuyIn() != null ? prev.averageBuyIn() : BigDecimal.ZERO;
        BigDecimal nextAvg = next.averageBuyIn() != null ? next.averageBuyIn() : BigDecimal.ZERO;
        BigDecimal weightedAvg = prev.quantity().multiply(prevAvg)
            .add(next.quantity().multiply(nextAvg))
            .divide(totalQty, 8, RoundingMode.HALF_UP);

        BigDecimal currentPrice = prev.currentPrice() != null ? prev.currentPrice() : next.currentPrice();
        String name = prev.name() != null ? prev.name() : next.name();

        return new HoldingAgg(totalQty, weightedAvg, currentPrice, name);
    }
}
