package com.picsou.crypto;

import com.picsou.model.Account;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.BalanceHistoryService;
import com.picsou.service.FinancialAssetService;
import com.picsou.service.HoldingComputeService;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the background half of the import ({@link CryptoImportService#finishImportPricing}) — the
 * price pipeline that {@code execute} defers off the request thread. The synchronous half (account +
 * raw transactions + unpriced holdings) and the /pricing long-poll are exercised end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class CryptoImportServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AccountHoldingRepository accountHoldingRepository;
    @Mock HoldingComputeService holdingComputeService;
    @Mock PriceService priceService;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock BalanceHistoryService balanceHistoryService;
    @Mock FinancialAssetService financialAssetService;
    @Mock FinancialAssetRepository assetRepository;
    @Mock TransactionTemplate txTemplate;

    private CryptoImportService service() {
        // Parsers aren't needed here (finishImportPricing takes the parser directly); an empty list
        // keeps the constructor's detection-order sort happy.
        return new CryptoImportService(List.of(), accountRepository, familyMemberRepository,
            transactionRepository, accountHoldingRepository, holdingComputeService, priceService,
            priceSnapshotRepository, balanceHistoryService, financialAssetService, assetRepository,
            txTemplate);
    }

    @Test
    void finishImportPricing_runsThePricePipelineInOrder_thenValuesAndRebuildsHistory() {
        CryptoImportService service = service();
        Account account = Account.builder().id(5L).currency("EUR").build();
        when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
        // No holdings yet → valueHoldings returns 0 without a live refresh; keeps the test focused on
        // ordering rather than valuation arithmetic (covered elsewhere).
        when(accountHoldingRepository.findByAccount_Id(5L)).thenReturn(List.of());

        CryptoCsvParser parser = org.mockito.Mockito.mock(CryptoCsvParser.class);
        lenient().when(parser.label()).thenReturn("Crypto.com App");

        // A valued BUY row (carries a fiat amount) → needsValuation() is false, so enrichValuations
        // doesn't need the snapshot/asset stubs.
        ParsedCryptoTx buy = new ParsedCryptoTx(LocalDate.of(2024, 1, 10), "Buy BTC", "BTC", "Bitcoin",
            TransactionType.BUY, null, new BigDecimal("0.1"), new BigDecimal("40000"),
            new BigDecimal("-4000"), "EUR", "buy");

        service.finishImportPricing(5L, List.of(), List.of(buy), parser);

        InOrder o = inOrder(priceService, transactionRepository, holdingComputeService, balanceHistoryService);
        // Backfill first (the valuation + stats overlay need the history)...
        o.verify(priceService).backfillHistoricalPrices(any(Set.class), any(LocalDate.class));
        // ...then the raw rows are replaced by the valued ones and holdings re-derived...
        o.verify(transactionRepository).deleteByAccountIdAndIsManualFalse(5L);
        o.verify(transactionRepository).saveAll(any());
        o.verify(holdingComputeService).recomputeHoldings(account);
        // ...and finally the account's daily value curve is rebuilt from the timeline.
        o.verify(balanceHistoryService).rebuild(eq(account), any());
    }

    @Test
    void finishImportPricing_missingAccount_isANoOp() {
        CryptoImportService service = service();
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        service.finishImportPricing(99L, List.of(), List.of(), org.mockito.Mockito.mock(CryptoCsvParser.class));

        verifyNoInteractions(priceService, holdingComputeService, balanceHistoryService, transactionRepository);
    }
}
