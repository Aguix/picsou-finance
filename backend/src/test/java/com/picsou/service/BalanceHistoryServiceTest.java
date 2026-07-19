package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconstruction is exercised with same-day transactions so the [start, today] loop runs a
 * single, deterministic iteration regardless of when the suite runs — the value/invested arithmetic
 * is what's under test, not the calendar walk.
 */
@ExtendWith(MockitoExtension.class)
class BalanceHistoryServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountHoldingRepository accountHoldingRepository;
    @Mock private BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock private PriceSnapshotRepository priceSnapshotRepository;
    @Mock private FinancialAssetRepository assetRepository;

    @Captor private ArgumentCaptor<List<BalanceSnapshot>> snapshotsCaptor;

    private static final LocalDate TODAY = LocalDate.now();

    private BalanceHistoryService service() {
        return new BalanceHistoryService(transactionRepository, accountHoldingRepository,
            balanceSnapshotRepository, priceSnapshotRepository, assetRepository);
    }

    private static Account account(Long id) {
        return Account.builder().id(id).type(AccountType.CRYPTO).build();
    }

    private static FinancialAsset asset(Long id, String symbol) {
        return FinancialAsset.builder().id(id).symbol(symbol).build();
    }

    private static PriceSnapshot price(FinancialAsset asset, LocalDate date, String eur) {
        return PriceSnapshot.builder().asset(asset).date(date).priceEur(new BigDecimal(eur)).build();
    }

    private static com.picsou.model.AccountHolding holding(String symbol, String qty) {
        return com.picsou.model.AccountHolding.builder()
            .asset(asset(100L, symbol))
            .quantity(new BigDecimal(qty))
            .build();
    }

    private static BalanceHistoryService.HistoryLeg leg(String ticker, TransactionType type,
                                                        String qty, String amount) {
        return new BalanceHistoryService.HistoryLeg(TODAY, ticker, type,
            new BigDecimal(qty), amount == null ? null : new BigDecimal(amount));
    }

    /**
     * Wire the price + snapshot lookups for a single BTC-priced account (600 EUR today). Leaves
     * {@code findByAccount_Id} unstubbed (Mockito's empty-list default) — the {@code rebuild}-direct
     * tests only touch it for the today anchor, which an empty holdings list handles fine.
     */
    private void stubBtcPricedAt600(Long accountId) {
        FinancialAsset btc = asset(100L, "BTC");
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceSnapshotRepository.findByAssetIdInAndDateBetween(any(), any(), any()))
            .thenReturn(List.of(price(btc, TODAY, "600")));
        when(balanceSnapshotRepository.findByAccountIdOrderByDateAsc(accountId)).thenReturn(List.of());
    }

    private BalanceSnapshot rebuildAndCaptureSingle(Account acct, List<BalanceHistoryService.HistoryLeg> legs) {
        service().rebuild(acct, legs);
        verify(balanceSnapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).hasSize(1);
        return snapshotsCaptor.getValue().get(0);
    }

    @Test
    void valuesTheHeldQuantityAtTheDaysPrice() {
        Account acct = account(1L);
        stubBtcPricedAt600(1L);

        BalanceSnapshot snap = rebuildAndCaptureSingle(acct,
            List.of(leg("BTC", TransactionType.BUY, "2", "-1000")));

        assertThat(snap.getDate()).isEqualTo(TODAY);
        assertThat(snap.getBalance()).isEqualByComparingTo("1200");        // 2 × 600
        assertThat(snap.getInvestedAmount()).isEqualByComparingTo("1000"); // BUY capital in
    }

    @Test
    void sellReleasesInvestedAndRewardAddsQuantityWithoutInvested() {
        Account acct = account(1L);
        stubBtcPricedAt600(1L);

        BalanceSnapshot snap = rebuildAndCaptureSingle(acct, List.of(
            leg("BTC", TransactionType.BUY, "2", "-1000"),
            leg("BTC", TransactionType.REWARD, "1", "0"),
            leg("BTC", TransactionType.SELL, "1", "500")));

        assertThat(snap.getBalance()).isEqualByComparingTo("1200");       // (2 + 1 − 1) × 600
        assertThat(snap.getInvestedAmount()).isEqualByComparingTo("500"); // 1000 in − 500 out
    }

    @Test
    void investedNeverGoesNegative() {
        Account acct = account(1L);
        stubBtcPricedAt600(1L);

        // A SELL larger than the tracked cost basis must floor invested at zero, not go negative.
        BalanceSnapshot snap = rebuildAndCaptureSingle(acct, List.of(
            leg("BTC", TransactionType.BUY, "3", "-100"),
            leg("BTC", TransactionType.SELL, "1", "900")));

        assertThat(snap.getInvestedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void updatesAnExistingSnapshotInPlaceRatherThanDuplicating() {
        Account acct = account(1L);
        FinancialAsset btc = asset(100L, "BTC");
        BalanceSnapshot existing = BalanceSnapshot.builder()
            .account(acct).date(TODAY).balance(new BigDecimal("999")).build();
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceSnapshotRepository.findByAssetIdInAndDateBetween(any(), any(), any()))
            .thenReturn(List.of(price(btc, TODAY, "600")));
        when(accountHoldingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(balanceSnapshotRepository.findByAccountIdOrderByDateAsc(1L)).thenReturn(List.of(existing));

        service().rebuild(acct, List.of(leg("BTC", TransactionType.BUY, "2", "-1000")));

        verify(balanceSnapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).containsExactly(existing); // same row, not a new one
        assertThat(existing.getBalance()).isEqualByComparingTo("1200");
    }

    @Test
    void doesNothingForAnEmptyTimeline() {
        service().rebuild(account(1L), List.of());

        verify(assetRepository, never()).findBySymbolIn(any());
        verify(balanceSnapshotRepository, never()).saveAll(any());
    }

    @Test
    void swallowsFailuresSoItNeverRollsBackTheTriggeringChange() {
        when(assetRepository.findBySymbolIn(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service().rebuild(account(1L),
            List.of(leg("BTC", TransactionType.BUY, "2", "-1000")))).doesNotThrowAnyException();

        verify(balanceSnapshotRepository, never()).saveAll(any());
    }

    @Test
    void rebuildFromTransactionsSourcesStoredRowsAndSkipsTickerlessOnes() {
        Account acct = account(5L);
        Transaction buy = Transaction.builder()
            .date(TODAY).ticker("BTC").txType(TransactionType.BUY)
            .quantity(new BigDecimal("2")).amount(new BigDecimal("-1000")).build();
        Transaction tickerless = Transaction.builder()
            .date(TODAY).txType(TransactionType.BUY).quantity(new BigDecimal("1")).build();
        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAsc(eq(5L), any()))
            .thenReturn(List.of(buy, tickerless));
        // The BTC buy of 2 exactly reproduces the account's holding → the gate passes and it rebuilds.
        when(accountHoldingRepository.findByAccount_Id(5L)).thenReturn(List.of(holding("BTC", "2")));
        stubBtcPricedAt600(5L);

        service().rebuildFromTransactions(acct);

        verify(balanceSnapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).hasSize(1);
        // Only the BTC buy contributes; the ticker-less row is dropped (no NPE, no phantom value).
        assertThat(snapshotsCaptor.getValue().get(0).getBalance()).isEqualByComparingTo("1200");
    }

    @Test
    void rebuildFromTransactionsSkipsAnAccountWithBalanceSyncedPositions() {
        Account acct = account(9L);
        // BTC is trade-derived, but AAPL is a bank-synced holding with no BUY/SELL rows: the timeline
        // can't reproduce the account, so its curve must be left intact rather than overwritten with a
        // partial one that drops AAPL. This is the mixed/bank-synced account the gate protects.
        Transaction btcBuy = Transaction.builder()
            .date(TODAY).ticker("BTC").txType(TransactionType.BUY)
            .quantity(new BigDecimal("2")).amount(new BigDecimal("-1000")).build();
        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAsc(eq(9L), any()))
            .thenReturn(List.of(btcBuy));
        when(accountHoldingRepository.findByAccount_Id(9L))
            .thenReturn(List.of(holding("BTC", "2"), holding("AAPL", "10")));

        service().rebuildFromTransactions(acct);

        verify(balanceSnapshotRepository, never()).saveAll(any());
        verify(assetRepository, never()).findBySymbolIn(any()); // bailed before the rebuild
    }
}
