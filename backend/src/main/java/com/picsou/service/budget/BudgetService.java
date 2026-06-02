package com.picsou.service.budget;

import com.picsou.dto.BudgetRequest;
import com.picsou.dto.BudgetResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Budget;
import com.picsou.model.Category;
import com.picsou.repository.BudgetRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Budget envelopes: a per-category monthly cap, scored against the member's active payday
 * cycle. The "spent" figure is derived on read from categorized transactions in the current
 * cycle — never stored — so it always reflects the latest sync. Expense transactions are
 * stored as negative amounts (outflow), so spent = −Σ(signed amount): a refund in the same
 * category correctly reduces the spent total.
 */
@Service
@Transactional(readOnly = true)
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final BudgetSettingsService budgetSettingsService;

    public BudgetService(
        BudgetRepository budgetRepository,
        CategoryRepository categoryRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        BudgetSettingsService budgetSettingsService
    ) {
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.budgetSettingsService = budgetSettingsService;
    }

    public List<BudgetResponse> findAll(Long memberId) {
        BudgetCycle.CycleRange cycle = currentCycle(memberId);
        return budgetRepository.findAllByMemberIdOrderByIdAsc(memberId).stream()
            .map(b -> toResponse(b, cycle))
            .toList();
    }

    @Transactional
    public BudgetResponse create(BudgetRequest req, Long memberId) {
        Category category = requireCategory(req.categoryId(), memberId);
        if (budgetRepository.existsByMemberIdAndCategoryId(memberId, category.getId())) {
            throw new IllegalArgumentException("A budget already exists for this category");
        }
        Budget budget = Budget.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .category(category)
            .monthlyLimit(req.monthlyLimit())
            .build();
        return toResponse(budgetRepository.save(budget), currentCycle(memberId));
    }

    @Transactional
    public BudgetResponse update(Long id, BudgetRequest req, Long memberId) {
        Budget budget = getOrThrow(id, memberId);
        // Allow re-pointing the envelope at another category, guarding the one-per-category rule.
        if (!budget.getCategory().getId().equals(req.categoryId())) {
            Category category = requireCategory(req.categoryId(), memberId);
            if (budgetRepository.existsByMemberIdAndCategoryId(memberId, category.getId())) {
                throw new IllegalArgumentException("A budget already exists for this category");
            }
            budget.setCategory(category);
        }
        budget.setMonthlyLimit(req.monthlyLimit());
        return toResponse(budgetRepository.save(budget), currentCycle(memberId));
    }

    @Transactional
    public void delete(Long id, Long memberId) {
        Budget budget = getOrThrow(id, memberId);
        budgetRepository.delete(budget);
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private BudgetResponse toResponse(Budget budget, BudgetCycle.CycleRange cycle) {
        BigDecimal signed = transactionRepository.sumByCategoryIdAndDateBetween(
            budget.getCategory().getId(), cycle.start(), cycle.end());
        BigDecimal spent = signed.negate(); // outflow is negative → positive "spent"
        BigDecimal limit = budget.getMonthlyLimit();
        BigDecimal percent = limit.compareTo(BigDecimal.ZERO) > 0
            ? spent.divide(limit, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        return BudgetResponse.from(budget, spent, percent, cycle.start(), cycle.end());
    }

    private BudgetCycle.CycleRange currentCycle(Long memberId) {
        return BudgetCycle.cycleFor(LocalDate.now(), budgetSettingsService.cycleStartDay(memberId));
    }

    private Category requireCategory(Long categoryId, Long memberId) {
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    private Budget getOrThrow(Long id, Long memberId) {
        return budgetRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Budget not found"));
    }
}
