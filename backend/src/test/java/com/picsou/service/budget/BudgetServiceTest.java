package com.picsou.service.budget;

import com.picsou.dto.BudgetResponse;
import com.picsou.model.Budget;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.repository.BudgetRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
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
class BudgetServiceTest {

    @Mock BudgetRepository budgetRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock BudgetSettingsService budgetSettingsService;

    @InjectMocks BudgetService service;

    private static final Long MEMBER_ID = 10L;

    private Budget budget(Long categoryId, String limit) {
        Category category = Category.builder()
            .id(categoryId).kind(CategoryKind.EXPENSE).name("Courses").color("#22c55e").build();
        return Budget.builder().id(1L).category(category).monthlyLimit(new BigDecimal(limit)).build();
    }

    @Test
    void findAll_computesSpentFromNegativeOutflow_underBudget() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        // Expenses are stored negative — €150 spent shows up as -150.
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-150.00"));

        List<BudgetResponse> result = service.findAll(MEMBER_ID);

        assertThat(result).hasSize(1);
        BudgetResponse r = result.get(0);
        assertThat(r.spent()).isEqualByComparingTo("150.00");
        assertThat(r.remaining()).isEqualByComparingTo("50.00");
        assertThat(r.percent()).isEqualByComparingTo("75.00");
        assertThat(r.overBudget()).isFalse();
    }

    @Test
    void findAll_flagsOverBudget_whenSpentExceedsLimit() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-250.00"));

        BudgetResponse r = service.findAll(MEMBER_ID).get(0);

        assertThat(r.spent()).isEqualByComparingTo("250.00");
        assertThat(r.remaining()).isEqualByComparingTo("-50.00");
        assertThat(r.overBudget()).isTrue();
    }

    @Test
    void findAll_refundReducesSpent() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        // −120 spent then +20 refund nets to −100 → €100 spent.
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-100.00"));

        BudgetResponse r = service.findAll(MEMBER_ID).get(0);

        assertThat(r.spent()).isEqualByComparingTo("100.00");
        assertThat(r.remaining()).isEqualByComparingTo("100.00");
    }
}
