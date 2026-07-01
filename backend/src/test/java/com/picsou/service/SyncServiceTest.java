package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.AccountData;
import com.picsou.port.BankConnectorPort.InstitutionData;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;

    @InjectMocks SyncService syncService;

    /** New accounts created from a requisition that already carries a logo get it copied over. */
    @Test
    void completeConnection_copiesLogoUrlFromRequisitionOntoNewAccount() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(10L)
            .member(member)
            .requisitionId("code-123")
            .institutionId("BNP_PARIBAS::FR")
            .institutionName("BNP Paribas")
            .logoUrl("https://logos.example/bnp.png")
            .status(RequisitionStatus.CREATED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("session-1");

        AccountData accountData = new AccountData("ext-1", "Compte Courant", "FR76...", "EUR", new BigDecimal("100"));
        when(bankConnector.fetchBalances("session-1")).thenReturn(List.of(accountData));

        when(accountRepository.findByExternalAccountIdAndMemberId("ext-1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ext-1", memberId))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(99L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenReturn(new AccountResponse(99L, "Compte Courant", null, "BNP Paribas", "EUR",
                new BigDecimal("100"), new BigDecimal("100"), null, false, "#6366f1", null,
                "https://logos.example/bnp.png", null, null, null));

        syncService.completeConnection("oauth-code", memberId);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getLogoUrl()).isEqualTo("https://logos.example/bnp.png");
    }

    /** A requisition created before logos existed gets backfilled on the next resync. */
    @Test
    void resyncAll_backfillsMissingLogoUrlFromInstitutionSearch() {
        Long memberId = 2L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(20L)
            .member(member)
            .requisitionId("session-2")
            .institutionId("BOURSOBANK::FR")
            .institutionName("BoursoBank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData match = new InstitutionData("BOURSOBANK::FR", "BoursoBank", "BNPAFRPP",
            "https://logos.example/bourso.png", "FR");
        when(bankConnector.searchInstitutions("BoursoBank", null)).thenReturn(List.of(match));

        when(bankConnector.fetchBalances("session-2")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/bourso.png");
    }

    /** A failed institution search during backfill must not break the resync loop. */
    @Test
    void resyncAll_backfillFailureDoesNotBreakSync() {
        Long memberId = 3L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(30L)
            .member(member)
            .requisitionId("session-3")
            .institutionId("UNKNOWN::FR")
            .institutionName("Unknown Bank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.searchInstitutions("Unknown Bank", null))
            .thenThrow(new RuntimeException("provider unavailable"));
        when(bankConnector.fetchBalances("session-3")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        // The failed logo lookup is swallowed inside ensureLogoUrl -- the resync itself
        // still completes normally (fetchBalances succeeds, requisition stays LINKED).
        assertThat(requisition.getLogoUrl()).isNull();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.LINKED);
        verify(requisitionRepository).save(requisition);
    }
}
