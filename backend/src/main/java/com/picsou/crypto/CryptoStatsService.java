package com.picsou.crypto;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.RewardKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes per-crypto statistics for a transaction-backed CRYPTO account: when each coin was first
 * bought, its diluted average buy-in, current value, and income earned per reward program —
 * whatever the source (Crypto.com App/Exchange, Kraken, Binance, Bybit, Bitstack, Ledger Live…).
 *
 * <p>Holdings (quantity, average buy-in, current price) are read from {@code account_holding}
 * — already derived by {@link com.picsou.service.HoldingComputeService} and priced by the scheduler.
 * The buy/reward timelines and per-program totals are computed from the {@code transaction} rows.
 *
 * <p>{@link #consolidatedStats(Long)} produces the same shape but pools every coin across <em>all</em>
 * of the member's CRYPTO accounts (CSV imports, Binance API sync, on-chain wallets), so the same
 * coin held on several platforms shows a single aggregated position. Cost/reward timelines only
 * exist for transaction-backed sources; balance-only sources contribute quantity &amp; value.
 */
@Service
@RequiredArgsConstructor
public class CryptoStatsService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final CoinGeckoPriceProvider coinGecko;

    private static final List<TransactionType> TX_TYPES =
        List.of(TransactionType.BUY, TransactionType.SELL, TransactionType.REWARD);

    @Transactional(readOnly = true)
    public CryptoStatsResponse stats(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        Map<String, AggHolding> holdings = new LinkedHashMap<>();
        for (AccountHolding h : accountHoldingRepository.findByAccount_Id(account.getId())) {
            holdings.merge(h.getTicker(), AggHolding.from(h), AggHolding::plus);
        }

        List<Transaction> txs = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAsc(
            account.getId(), TX_TYPES);

        return assemble(holdings, txs);
    }

    /**
     * Consolidated view: every coin pooled across all of the member's CRYPTO accounts, keyed by
     * ticker. Holdings are summed per ticker (weighted average buy-in over the sources that carry
     * a cost basis); wallet accounts that hold no per-coin {@code account_holding} contribute
     * their EUR balance under the coin symbol kept in {@code provider} (BTC / ETH / SOL).
     */
    @Transactional(readOnly = true)
    public CryptoStatsResponse consolidatedStats(Long memberId) {
        List<Account> cryptoAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)
            .stream().filter(a -> a.getType() == AccountType.CRYPTO).toList();

        Map<String, AggHolding> holdings = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>(cryptoAccounts.size());
        for (Account acc : cryptoAccounts) {
            ids.add(acc.getId());
            List<AccountHolding> hs = accountHoldingRepository.findByAccount_Id(acc.getId());
            if (hs.isEmpty()) {
                // Wallet-style account: no per-coin holding, just an EUR balance under the coin symbol.
                if (acc.getProvider() != null && !acc.getProvider().isBlank()
                    && acc.getCurrentBalance() != null && acc.getCurrentBalance().signum() > 0) {
                    String ticker = acc.getProvider().toUpperCase();
                    holdings.merge(ticker, AggHolding.valueOnly(ticker, acc.getCurrentBalance()), AggHolding::plus);
                }
            } else {
                for (AccountHolding h : hs) {
                    holdings.merge(h.getTicker(), AggHolding.from(h), AggHolding::plus);
                }
            }
        }

        List<Transaction> txs = ids.isEmpty()
            ? List.of()
            : transactionRepository.findByAccountIdInAndTxTypeInOrderByDateAsc(ids, TX_TYPES);

        return assemble(holdings, txs);
    }

    /**
     * Builds the response from a per-ticker aggregated-holding map and a date-ASC transaction list.
     * Iterates over the union of tickers seen in either source, so a coin held without transactions
     * (API-synced exchange, wallet) still surfaces with quantity &amp; value, and a fully-sold coin
     * that produced rewards still shows its income.
     */
    private CryptoStatsResponse assemble(Map<String, AggHolding> holdings, List<Transaction> txs) {
        // Group transactions by ticker, preserving date-ASC order.
        Map<String, List<Transaction>> byTicker = new LinkedHashMap<>();
        for (Transaction t : txs) {
            if (t.getTicker() == null || t.getTicker().isBlank()) {
                continue;
            }
            byTicker.computeIfAbsent(t.getTicker(), k -> new ArrayList<>()).add(t);
        }

        Set<String> tickers = new LinkedHashSet<>(byTicker.keySet());
        tickers.addAll(holdings.keySet());
        Map<String, String> logoUrls = coinGecko.getLogoUrls(tickers);

        List<CryptoStatsResponse.AssetStat> assets = new ArrayList<>();
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalRewards = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        Map<RewardKind, BigDecimal> totalsByKind = new EnumMap<>(RewardKind.class);

        for (String ticker : tickers) {
            List<Transaction> list = byTicker.getOrDefault(ticker, List.of());
            AggHolding holding = holdings.get(ticker);

            List<CryptoStatsResponse.BuyEvent> buyEvents = new ArrayList<>();
            List<CryptoStatsResponse.SellEvent> sellEvents = new ArrayList<>();
            List<CryptoStatsResponse.RewardEvent> rewardEvents = new ArrayList<>();
            Map<RewardKind, BigDecimal> rewardsByKind = new EnumMap<>(RewardKind.class);
            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal rewardsEur = BigDecimal.ZERO;
            BigDecimal rewardsQty = BigDecimal.ZERO;
            String name = holding != null ? holding.name() : ticker;
            String logoUrl = logoUrls.get(ticker.toUpperCase());

            for (Transaction t : list) {
                BigDecimal value = t.getAmount() != null ? t.getAmount().abs() : BigDecimal.ZERO;
                if (t.getName() != null) {
                    name = t.getName();
                }
                if (t.getTxType() == TransactionType.BUY) {
                    invested = invested.add(value);
                    buyEvents.add(new CryptoStatsResponse.BuyEvent(
                        t.getDate(), t.getQuantity(), t.getPricePerUnit(), value));
                } else if (t.getTxType() == TransactionType.SELL) {
                    sellEvents.add(new CryptoStatsResponse.SellEvent(
                        t.getDate(), t.getQuantity(), t.getPricePerUnit(), value));
                } else if (t.getTxType() == TransactionType.REWARD) {
                    RewardKind kind = t.getRewardKind() != null ? t.getRewardKind() : RewardKind.OTHER;
                    rewardsEur = rewardsEur.add(value);
                    rewardsQty = rewardsQty.add(t.getQuantity() != null ? t.getQuantity() : BigDecimal.ZERO);
                    rewardsByKind.merge(kind, value, BigDecimal::add);
                    totalsByKind.merge(kind, value, BigDecimal::add);
                    rewardEvents.add(new CryptoStatsResponse.RewardEvent(
                        t.getDate(), kind, t.getQuantity(), value));
                }
            }

            BigDecimal quantity = holding != null ? holding.quantity() : BigDecimal.ZERO;
            BigDecimal avgBuyIn = holding != null ? holding.averageBuyIn() : null;
            BigDecimal currentPrice = holding != null ? holding.price() : null;
            BigDecimal valueOnly = holding != null ? holding.valueOnlyEur() : BigDecimal.ZERO;
            BigDecimal pricedValue = currentPrice != null ? quantity.multiply(currentPrice) : null;
            BigDecimal currentValue = pricedValue;
            if (valueOnly.signum() > 0) {
                currentValue = (pricedValue != null ? pricedValue : BigDecimal.ZERO).add(valueOnly);
            }
            BigDecimal costBasis = avgBuyIn != null ? quantity.multiply(avgBuyIn) : null;
            BigDecimal unrealizedPnl = (currentValue != null && costBasis != null)
                ? currentValue.subtract(costBasis) : null;

            // Skip coins that are fully sold and never produced a reward (nothing to show),
            // but still let their buys/sells count toward the invested total below.
            boolean hasPosition = quantity.signum() > 0 || valueOnly.signum() > 0;
            boolean hasRewards = rewardsEur.signum() > 0;
            if (hasPosition || hasRewards) {
                LocalDate firstDate = list.isEmpty() ? null : list.get(0).getDate();
                LocalDate lastDate = list.isEmpty() ? null : list.get(list.size() - 1).getDate();
                List<CryptoStatsResponse.CostPoint> costSeries = buildCostSeries(list);
                List<CryptoStatsResponse.PricePoint> priceSeries = buildPriceSeries(ticker, firstDate);
                assets.add(new CryptoStatsResponse.AssetStat(
                    ticker, name, logoUrl, quantity, avgBuyIn, currentPrice, currentValue, costBasis,
                    invested, rewardsEur, rewardsQty, unrealizedPnl,
                    firstDate, lastDate,
                    rewardsByKind, buyEvents, sellEvents, rewardEvents, costSeries, priceSeries));
            }

            totalInvested = totalInvested.add(invested);
            totalRewards = totalRewards.add(rewardsEur);
            if (currentValue != null) {
                totalCurrentValue = totalCurrentValue.add(currentValue);
            }
        }

        assets.sort(Comparator.comparing(
            (CryptoStatsResponse.AssetStat a) -> a.currentValueEur() != null ? a.currentValueEur() : BigDecimal.ZERO)
            .reversed());

        return new CryptoStatsResponse(
            assets,
            new CryptoStatsResponse.Totals(totalInvested, totalRewards, totalCurrentValue, totalsByKind));
    }

    /**
     * Running diluted average buy-in (cost basis per unit) after each transaction event — the same
     * VWAP rule {@link com.picsou.service.HoldingComputeService} applies for the final holding, but
     * emitted as a timeline. BUY and REWARD add quantity (REWARD at price 0, diluting the average);
     * SELL reduces quantity without changing the average. One point per event date (last wins).
     */
    private List<CryptoStatsResponse.CostPoint> buildCostSeries(List<Transaction> list) {
        BigDecimal numerator = BigDecimal.ZERO;   // Σ(qty × price) over BUY + REWARD
        BigDecimal denominator = BigDecimal.ZERO;  // Σ(qty) over BUY + REWARD
        Map<LocalDate, BigDecimal> byDate = new LinkedHashMap<>();

        for (Transaction t : list) {
            BigDecimal qty = t.getQuantity() != null ? t.getQuantity() : BigDecimal.ZERO;
            if (t.getTxType() == TransactionType.BUY || t.getTxType() == TransactionType.REWARD) {
                BigDecimal price = t.getPricePerUnit() != null ? t.getPricePerUnit() : BigDecimal.ZERO;
                numerator = numerator.add(qty.multiply(price));
                denominator = denominator.add(qty);
            }
            // SELL leaves the average untouched (cost basis is reduced proportionally elsewhere).
            BigDecimal avg = denominator.signum() > 0
                ? numerator.divide(denominator, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            byDate.put(t.getDate(), avg);
        }

        List<CryptoStatsResponse.CostPoint> series = new ArrayList<>(byDate.size());
        byDate.forEach((date, avg) -> series.add(new CryptoStatsResponse.CostPoint(date, avg)));
        return series;
    }

    /** Daily market-price history (EUR) for the coin from {@code firstDate} to today. */
    private List<CryptoStatsResponse.PricePoint> buildPriceSeries(String ticker, LocalDate firstDate) {
        if (firstDate == null) {
            return List.of();
        }
        List<PriceSnapshot> snapshots = priceSnapshotRepository.findByTickerInAndDateBetween(
            Set.of(ticker.toUpperCase()), firstDate, LocalDate.now());
        List<CryptoStatsResponse.PricePoint> series = new ArrayList<>(snapshots.size());
        for (PriceSnapshot s : snapshots) {
            series.add(new CryptoStatsResponse.PricePoint(s.getDate(), s.getPriceEur()));
        }
        return series;
    }

    /**
     * Aggregated holding for a coin across one or more sources. {@code costSum}/{@code qtyWithCost}
     * carry the weighted-average numerator/denominator so several sources with their own average
     * buy-in combine correctly; {@code valueOnlyEur} holds EUR balances from sources that report no
     * quantity (on-chain wallets).
     */
    private record AggHolding(
        BigDecimal quantity,
        BigDecimal costSum,
        BigDecimal qtyWithCost,
        BigDecimal price,
        BigDecimal valueOnlyEur,
        String name
    ) {
        /** Weighted average buy-in over the sources that carry a cost basis, or null if none do. */
        BigDecimal averageBuyIn() {
            return qtyWithCost.signum() > 0
                ? costSum.divide(qtyWithCost, 8, RoundingMode.HALF_UP) : null;
        }

        AggHolding plus(AggHolding o) {
            return new AggHolding(
                quantity.add(o.quantity),
                costSum.add(o.costSum),
                qtyWithCost.add(o.qtyWithCost),
                price != null ? price : o.price,
                valueOnlyEur.add(o.valueOnlyEur),
                name != null ? name : o.name);
        }

        static AggHolding from(AccountHolding h) {
            BigDecimal qty = h.getQuantity() != null ? h.getQuantity() : BigDecimal.ZERO;
            boolean hasCost = h.getAverageBuyIn() != null && h.getAverageBuyIn().signum() > 0;
            return new AggHolding(
                qty,
                hasCost ? qty.multiply(h.getAverageBuyIn()) : BigDecimal.ZERO,
                hasCost ? qty : BigDecimal.ZERO,
                h.getCurrentPrice(),
                BigDecimal.ZERO,
                h.getName());
        }

        static AggHolding valueOnly(String ticker, BigDecimal eur) {
            return new AggHolding(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, eur, ticker);
        }
    }
}
