package com.picsou.crypto;

import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.model.RewardKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoStatsServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountHoldingRepository accountHoldingRepository;
    @Mock private PriceSnapshotRepository priceSnapshotRepository;
    @Mock private FinancialAssetRepository assetRepository;

    @InjectMocks private CryptoStatsService service;

    private static Account account(long id, AccountType type, String provider, String balance) {
        return Account.builder()
            .id(id).type(type).provider(provider)
            .currentBalance(new BigDecimal(balance))
            .build();
    }

    private static FinancialAsset asset(String symbol, AssetType type) {
        return FinancialAsset.builder().id((long) symbol.hashCode()).symbol(symbol).name(symbol).type(type).build();
    }

    /** Holding with no owning account — for the single-account {@code stats()} path. */
    private static AccountHolding holding(String ticker, String qty, String avg, String price) {
        return AccountHolding.builder()
            .asset(asset(ticker, AssetType.CRYPTO))
            .quantity(new BigDecimal(qty))
            .averageBuyIn(avg == null ? null : new BigDecimal(avg))
            .currentPrice(price == null ? null : new BigDecimal(price))
            .build();
    }

    /** Holding owned by a specific account — for the consolidated (member-wide) path. */
    private static AccountHolding holdingOn(long accountId, String ticker, AssetType type,
                                            String qty, String avg, String price) {
        return AccountHolding.builder()
            .account(Account.builder().id(accountId).build())
            .asset(asset(ticker, type))
            .quantity(new BigDecimal(qty))
            .averageBuyIn(avg == null ? null : new BigDecimal(avg))
            .currentPrice(price == null ? null : new BigDecimal(price))
            .build();
    }

    private static Transaction buy(long accountId, String ticker, String date, String qty, String price, String amount) {
        return Transaction.builder()
            .account(Account.builder().id(accountId).build())
            .ticker(ticker).date(LocalDate.parse(date))
            .txType(TransactionType.BUY)
            .quantity(new BigDecimal(qty)).pricePerUnit(new BigDecimal(price))
            .amount(new BigDecimal(amount)).description("buy")
            .build();
    }

    private static Transaction reward(long accountId, String ticker, String date, String qty, String amount, RewardKind kind) {
        return Transaction.builder()
            .account(Account.builder().id(accountId).build())
            .ticker(ticker).date(LocalDate.parse(date))
            .txType(TransactionType.REWARD).rewardKind(kind)
            .quantity(new BigDecimal(qty)).pricePerUnit(BigDecimal.ZERO)
            .amount(new BigDecimal(amount)).description("reward")
            .build();
    }

    /**
     * The same coin held on Crypto.com (with a cost basis), on Binance (no cost basis) and in an
     * on-chain wallet (EUR balance only, no per-coin holding) pools into a single BTC position:
     * quantity sums the holdings, the wallet's EUR balance adds to the value, and the average buy-in
     * is weighted over only the sources that carry a cost.
     */
    @Test
    void consolidatedStats_poolsSameCoinAcrossPlatformsAndWallets() {
        Account cryptocom = account(1, AccountType.CRYPTO, "Crypto.com", "0");
        Account binance = account(2, AccountType.CRYPTO, "BINANCE", "0");
        Account wallet = account(3, AccountType.CRYPTO, "BTC", "1000");   // wallet: value-only
        Account bank = account(4, AccountType.CHECKING, "Bank", "5000");  // must be ignored

        when(accountHoldingRepository.findByMemberIdAndAssetType(7L, AssetType.CRYPTO))
            .thenReturn(List.of(
                holdingOn(1, "BTC", AssetType.CRYPTO, "1", "20000", "30000"),
                holdingOn(2, "BTC", AssetType.CRYPTO, "0.5", null, "30000")));  // Binance: no cost basis
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(cryptocom, binance, wallet, bank));

        when(transactionRepository.findByAccountIdInAndTxTypeInOrderByDateAsc(eq(List.of(1L, 2L, 3L)), any()))
            .thenReturn(List.of(buy(1L, "BTC", "2024-01-10", "1", "20000", "20000")));
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(asset("BTC", AssetType.CRYPTO)));

        CryptoStatsResponse res = service.consolidatedStats(7L);

        assertThat(res.assets()).hasSize(1);
        CryptoStatsResponse.AssetStat btc = res.assets().get(0);
        assertThat(btc.ticker()).isEqualTo("BTC");
        assertThat(btc.quantity()).isEqualByComparingTo("1.5");                 // 1 + 0.5
        assertThat(btc.averageBuyIn()).isEqualByComparingTo("20000");           // weighted over cost-bearing source
        // value = 1.5 × 30000 (priced holdings) + 1000 (wallet EUR balance)
        assertThat(btc.currentValueEur()).isEqualByComparingTo("46000");
        assertThat(btc.totalInvestedEur()).isEqualByComparingTo("20000");       // from the BUY transaction
        assertThat(res.totals().currentValueEur()).isEqualByComparingTo("46000");
    }

    /** A coin held only on an exchange (no transactions, no cost) still surfaces with quantity & value. */
    @Test
    void consolidatedStats_showsHoldingOnlyCoinWithoutTransactions() {
        Account binance = account(2, AccountType.CRYPTO, "BINANCE", "0");
        when(accountHoldingRepository.findByMemberIdAndAssetType(7L, AssetType.CRYPTO))
            .thenReturn(List.of(holdingOn(2, "ETH", AssetType.CRYPTO, "2", null, "2500")));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(binance));
        when(transactionRepository.findByAccountIdInAndTxTypeInOrderByDateAsc(any(), any()))
            .thenReturn(List.of());
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of());

        CryptoStatsResponse res = service.consolidatedStats(7L);

        assertThat(res.assets()).hasSize(1);
        CryptoStatsResponse.AssetStat eth = res.assets().get(0);
        assertThat(eth.ticker()).isEqualTo("ETH");
        assertThat(eth.quantity()).isEqualByComparingTo("2");
        assertThat(eth.currentValueEur()).isEqualByComparingTo("5000");
        assertThat(eth.averageBuyIn()).isNull();          // no cost basis from the exchange
        assertThat(eth.firstBuyDate()).isNull();          // no transactions
        assertThat(eth.costSeries()).isEmpty();
        assertThat(eth.rewardsYieldPct()).isNull();       // nothing invested → no denominator
    }

    /**
     * Crypto held inside a mixed brokerage account (Trade Republic stores its coins as XF000 holdings
     * on a COMPTE_TITRES account) is pooled by the asset's type — while the account's stock holdings
     * and stock BUY/SELL rows are excluded from the crypto recap.
     */
    @Test
    void consolidatedStats_poolsCryptoFromMixedBrokerageAccount_excludesStock() {
        Account tr = account(5, AccountType.COMPTE_TITRES, "Trade Republic", "0");
        // findByMemberIdAndAssetType only returns the crypto holding; the AAPL stock holding is filtered out by the query.
        when(accountHoldingRepository.findByMemberIdAndAssetType(7L, AssetType.CRYPTO))
            .thenReturn(List.of(holdingOn(5, "BTC", AssetType.CRYPTO, "0.01", "40000", "84500")));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(tr));
        // The mixed account carries both a crypto and a stock BUY row.
        when(transactionRepository.findByAccountIdInAndTxTypeInOrderByDateAsc(eq(List.of(5L)), any()))
            .thenReturn(List.of(
                buy(5L, "BTC", "2024-02-01", "0.01", "40000", "400"),
                buy(5L, "AAPL", "2024-02-02", "3", "150", "450")));
        when(assetRepository.findBySymbolIn(any()))
            .thenReturn(List.of(asset("BTC", AssetType.CRYPTO), asset("AAPL", AssetType.STOCK)));

        CryptoStatsResponse res = service.consolidatedStats(7L);

        assertThat(res.assets()).hasSize(1);
        assertThat(res.assets().get(0).ticker()).isEqualTo("BTC");
        assertThat(res.assets()).noneMatch(a -> a.ticker().equals("AAPL"));
        assertThat(res.assets().get(0).totalInvestedEur()).isEqualByComparingTo("400");  // BTC buy only
    }

    /** Single-account stats keeps working through the shared assembler. */
    @Test
    void stats_singleAccount_unchanged() {
        Account cryptocom = account(1, AccountType.CRYPTO, "Crypto.com", "0");
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(java.util.Optional.of(cryptocom));
        when(accountHoldingRepository.findByAccount_Id(1L))
            .thenReturn(List.of(holding("BTC", "1", "20000", "30000")));
        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAsc(eq(1L), any()))
            .thenReturn(List.of(buy(1L, "BTC", "2024-01-10", "1", "20000", "20000")));
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of());

        CryptoStatsResponse res = service.stats(1L, 7L);

        assertThat(res.assets()).hasSize(1);
        assertThat(res.assets().get(0).quantity()).isEqualByComparingTo("1");
        assertThat(res.assets().get(0).currentValueEur()).isEqualByComparingTo("30000");
    }

    /**
     * Reward income is reported as a yield: free coins earned relative to the capital invested,
     * both per coin and in the totals.
     */
    @Test
    void stats_computesRewardYieldOverInvested() {
        Account cryptocom = account(1, AccountType.CRYPTO, "Crypto.com", "0");
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(java.util.Optional.of(cryptocom));
        when(accountHoldingRepository.findByAccount_Id(1L))
            .thenReturn(List.of(holding("BTC", "1.1", "20000", "30000")));
        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAsc(eq(1L), any()))
            .thenReturn(List.of(
                buy(1L, "BTC", "2024-01-10", "1", "20000", "20000"),
                reward(1L, "BTC", "2024-06-10", "0.1", "1000", RewardKind.EARN)));
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of());

        CryptoStatsResponse res = service.stats(1L, 7L);

        CryptoStatsResponse.AssetStat btc = res.assets().get(0);
        assertThat(btc.totalRewardsEur()).isEqualByComparingTo("1000");
        assertThat(btc.rewardsYieldPct()).isEqualByComparingTo("5.00");     // 1000 / 20000 × 100
        assertThat(res.totals().rewardsYieldPct()).isEqualByComparingTo("5.00");
        assertThat(res.totals().rewardsByKindEur().get(RewardKind.EARN)).isEqualByComparingTo("1000");
    }
}
