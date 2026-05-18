package com.picsou.service;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Goal;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;
    @Mock GoalManualContributionRepository manualContributionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock HistoryService historyService;

    @InjectMocks GoalService goalService;

    @Test
    void progressCalculation_onTrack() {
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        Goal goal = Goal.builder()
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(account))
            .build();

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, true, "#6366f1", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("5000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
        assertThat(progress.targetAmount()).isEqualByComparingTo("20000");
        assertThat(progress.monthsLeft()).isEqualTo(6L);
        // monthlyNeeded = (20000 - 5000) / 6 = 2500
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo("2500.00");
        assertThat(progress.percentComplete()).isEqualByComparingTo("25.0000");
    }

    @Test
    void isOnTrack_false_whenPastEffectivesBelowPastObjectives() {
        // 3 past months, each with snapshot delta = 1000€.
        // Target 12000, current 0, deadline +3 months → monthlyNeeded = 4000.
        // sumObjectivePast = 3 * 4000 = 12000 ; sumEffectivePast = 3 * 1000 = 3000 → behind.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = LocalDate.now().minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, true, "#000", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        // Per-month-end balances so each past month delta = 1000.
        // M-4 end = 0, M-3 end = 1000, M-2 end = 2000, M-1 end = 3000.
        java.time.YearMonth now = java.time.YearMonth.now();
        for (int i = 1; i <= 3; i++) {
            java.time.YearMonth past = now.minusMonths(i);
            LocalDate prevEnd = past.minusMonths(1).atEndOfMonth();
            LocalDate thisEnd = past.atEndOfMonth();
            BigDecimal prevBalance = new BigDecimal(String.valueOf((4 - i - 1) * 1000));
            BigDecimal thisBalance = new BigDecimal(String.valueOf((4 - i) * 1000));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, prevEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(prevBalance).date(prevEnd).build()));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, thisEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(thisBalance).date(thisEnd).build()));
        }
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isFalse();
    }
}
