package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinancialAsset;
import com.picsou.model.BoursoSession;
import com.picsou.port.BoursoPort;
import com.picsou.port.BoursoPort.BoursoAccountData;
import com.picsou.port.BoursoPort.BoursoPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoursoSyncServiceTest {

    @Mock BoursoPort boursoPort;
    @Mock BoursoSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock FinancialAssetService financialAssetService;

    @InjectMocks BoursoSyncService service;

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    /** Wire up the common happy path: a stored session yielding one new PEA account. */
    private void arrangeSession(Long memberId, FamilyMember member, BoursoAccountData accountData) {
        BoursoSession stored = BoursoSession.builder()
            .member(member)
            .sessionCookies("enc-cookies")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(stored));
        when(encryption.decrypt("enc-cookies")).thenReturn("plain-cookies");
        when(boursoPort.fetchAccounts("plain-cookies")).thenReturn(List.of(accountData));

        when(accountRepository.findByExternalAccountIdAndMemberId("bourso_pea", memberId))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("1000")));
    }

    @Test
    void sync_isinPosition_registersTheTickerAsAStockViaGetOrCreateStock() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        // A real market ISIN → OpenFIGI resolves it to a Yahoo ticker; that ticker must be
        // registered as a stock (yahoo_symbol populated) rather than left ambiguous.
        BoursoPosition pos = new BoursoPosition(
            "FR0000120271", "TTE", "TotalEnergies", bd("10"), bd("50"), bd("60"));
        BoursoAccountData accountData = new BoursoAccountData(
            "bourso_pea", "PEA", AccountType.PEA, bd("600"), List.of(pos), List.of());
        arrangeSession(memberId, member, accountData);

        when(isinConverter.resolve("FR0000120271"))
            .thenReturn(new TickerResult("TTE.PA", "TotalEnergies SE"));
        when(financialAssetService.getOrCreateStock("TTE.PA"))
            .thenReturn(FinancialAsset.builder().symbol("TTE.PA").yahooSymbol("TTE.PA").build());

        service.sync(memberId);

        verify(financialAssetService).getOrCreateStock("TTE.PA");
        verify(financialAssetService, never()).getOrCreate("TTE.PA");
    }

    @Test
    void sync_rawBrokerSymbolWithoutIsin_staysAmbiguousViaGetOrCreate() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        // No ISIN → the bare broker symbol is unvalidated (could be a stock or a coin), so it must
        // NOT be minted as a stock with a spurious yahoo_symbol — it stays on the generic path.
        BoursoPosition pos = new BoursoPosition(
            null, "SOMESYM", "Some Symbol", bd("3"), bd("100"), bd("110"));
        BoursoAccountData accountData = new BoursoAccountData(
            "bourso_pea", "PEA", AccountType.PEA, bd("330"), List.of(pos), List.of());
        arrangeSession(memberId, member, accountData);

        when(financialAssetService.getOrCreate("SOMESYM"))
            .thenReturn(FinancialAsset.builder().symbol("SOMESYM").build());

        service.sync(memberId);

        verify(financialAssetService).getOrCreate("SOMESYM");
        verify(financialAssetService, never()).getOrCreateStock(any());
        verify(isinConverter, never()).resolve(any());
    }
}
