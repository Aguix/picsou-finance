package com.picsou.crypto;

import com.picsou.model.RewardKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Per-crypto statistics for a transaction-backed CRYPTO account (or the consolidated view):
 * when each coin was first bought, its diluted average buy-in, current value, and the
 * income earned from each reward program (Earn, staking, Supercharger, Airdrop Arena, ...).
 *
 * <p>{@code rewardsYieldPct} is the return earned as free coins relative to the capital invested
 * ({@code totalRewardsEur / totalInvestedEur}, as a percentage), so a position's income can be read
 * as an interest rate rather than a bare euro figure. It is {@code null} when nothing was invested
 * (a coin held only from rewards has no denominator).
 */
public record CryptoStatsResponse(
    List<AssetStat> assets,
    Totals totals
) {

    public record AssetStat(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyIn,
        BigDecimal currentPrice,
        BigDecimal currentValueEur,
        BigDecimal costBasisEur,
        BigDecimal totalInvestedEur,
        BigDecimal totalRewardsEur,
        BigDecimal totalRewardsQty,
        BigDecimal rewardsYieldPct,
        BigDecimal unrealizedPnlEur,
        LocalDate firstBuyDate,
        LocalDate lastActivityDate,
        Map<RewardKind, BigDecimal> rewardsByKindEur,
        List<BuyEvent> buyEvents,
        List<SellEvent> sellEvents,
        List<RewardEvent> rewardEvents,
        List<CostPoint> costSeries,
        List<PricePoint> priceSeries
    ) {}

    public record BuyEvent(LocalDate date, BigDecimal quantity, BigDecimal pricePerUnit, BigDecimal valueEur) {}

    public record SellEvent(LocalDate date, BigDecimal quantity, BigDecimal pricePerUnit, BigDecimal valueEur) {}

    public record RewardEvent(LocalDate date, RewardKind kind, BigDecimal quantity, BigDecimal valueEur) {}

    /** Running diluted average buy-in after each transaction event (the cost-basis timeline). */
    public record CostPoint(LocalDate date, BigDecimal averageBuyIn) {}

    /** Historical market price (EUR) of the coin, from the daily price snapshots. */
    public record PricePoint(LocalDate date, BigDecimal priceEur) {}

    public record Totals(
        BigDecimal totalInvestedEur,
        BigDecimal totalRewardsEur,
        BigDecimal currentValueEur,
        BigDecimal rewardsYieldPct,
        Map<RewardKind, BigDecimal> rewardsByKindEur
    ) {}
}
