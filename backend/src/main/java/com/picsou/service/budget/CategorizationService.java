package com.picsou.service.budget;

import com.picsou.dto.CategorizationRuleRequest;
import com.picsou.dto.CategorizationRuleResponse;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.CategorizationRule;
import com.picsou.model.Category;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;
import com.picsou.model.Transaction;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Assigns managed {@link Category} to transactions, three ways:
 * <ol>
 *   <li><b>Auto</b> — {@link #apply} runs the member's rules (highest priority first) over
 *       a transaction's counterparty/description during sync and manual entry.</li>
 *   <li><b>Manual</b> — {@link #categorize} sets a category by hand and can
 *       {@link #learnRule learn} a COUNTERPARTY rule so the next occurrence is automatic.</li>
 *   <li><b>Bulk</b> — {@link #recategorizeUncategorized} re-runs rules over everything still
 *       uncategorized (e.g. right after a new rule is created).</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class CategorizationService {

    private final CategorizationRuleRepository ruleRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public CategorizationService(
        CategorizationRuleRepository ruleRepository,
        CategoryRepository categoryRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.ruleRepository = ruleRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    // ─── Rule CRUD ────────────────────────────────────────────────────────────

    public List<CategorizationRuleResponse> findAllRules(Long memberId) {
        return ruleRepository.findAllByMemberIdOrderByPriorityDescIdAsc(memberId).stream()
            .map(CategorizationRuleResponse::from)
            .toList();
    }

    @Transactional
    public CategorizationRuleResponse createRule(CategorizationRuleRequest req, Long memberId) {
        Category category = requireCategory(req.categoryId(), memberId);
        CategorizationRule rule = CategorizationRule.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .matchType(req.matchType())
            .pattern(req.pattern().trim())
            .category(category)
            .priority(req.priority() != null ? req.priority() : 0)
            .source(RuleSource.USER)
            .build();
        return CategorizationRuleResponse.from(ruleRepository.save(rule));
    }

    @Transactional
    public CategorizationRuleResponse updateRule(Long id, CategorizationRuleRequest req, Long memberId) {
        CategorizationRule rule = ruleRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.rule(id));
        rule.setMatchType(req.matchType());
        rule.setPattern(req.pattern().trim());
        rule.setCategory(requireCategory(req.categoryId(), memberId));
        if (req.priority() != null) {
            rule.setPriority(req.priority());
        }
        return CategorizationRuleResponse.from(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id, Long memberId) {
        CategorizationRule rule = ruleRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.rule(id));
        ruleRepository.delete(rule);
    }

    // ─── Auto-apply ───────────────────────────────────────────────────────────

    /**
     * Apply the given pre-loaded rules to one transaction, setting its category if a rule
     * matches. Rules must be ordered highest-priority-first (the first match wins). Returns
     * true if a category was assigned. Taking rules as a parameter lets the sync loop load
     * them once and reuse across a whole batch.
     */
    public boolean apply(Transaction tx, List<CategorizationRule> rules) {
        if (tx.getCategoryRef() != null) {
            return false; // never override an existing assignment
        }
        String counterparty = safeLower(tx.getCounterparty());
        String description = safeLower(tx.getDescription());
        for (CategorizationRule rule : rules) {
            if (matches(rule, counterparty, description)) {
                tx.setCategoryRef(rule.getCategory());
                return true;
            }
        }
        return false;
    }

    /** Convenience overload that loads the member's rules itself. */
    public boolean apply(Transaction tx, Long memberId) {
        return apply(tx, ruleRepository.findAllByMemberIdOrderByPriorityDescIdAsc(memberId));
    }

    private boolean matches(CategorizationRule rule, String counterparty, String description) {
        String pattern = rule.getPattern().toLowerCase(Locale.ROOT);
        if (rule.getMatchType() == RuleMatchType.COUNTERPARTY) {
            return !counterparty.isEmpty() && counterparty.equals(pattern);
        }
        // KEYWORD: substring match against either field
        return counterparty.contains(pattern) || description.contains(pattern);
    }

    // ─── Manual categorization + learning ─────────────────────────────────────

    /**
     * Assign a category to a transaction by hand. When {@code createRule} is set and the
     * transaction has a counterparty, also learn an AUTO COUNTERPARTY rule so future
     * transactions from the same counterparty categorize themselves.
     */
    @Transactional
    public void categorize(Long transactionId, Long categoryId, boolean createRule, Long memberId) {
        Transaction tx = transactionRepository.findByIdAndAccountMemberId(transactionId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.transaction(transactionId));
        Category category = requireCategory(categoryId, memberId);
        tx.setCategoryRef(category);
        transactionRepository.save(tx);

        if (createRule && tx.getCounterparty() != null && !tx.getCounterparty().isBlank()) {
            learnRule(tx.getCounterparty().trim(), categoryId, memberId);
        }
    }

    /**
     * Create an AUTO COUNTERPARTY rule for {@code counterparty → category} if one does not
     * already exist for that exact counterparty. Idempotent so repeated manual
     * categorizations of the same merchant don't pile up duplicate rules.
     */
    @Transactional
    public void learnRule(String counterparty, Long categoryId, Long memberId) {
        boolean exists = ruleRepository
            .findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(memberId, RuleMatchType.COUNTERPARTY, counterparty)
            .isPresent();
        if (exists) {
            return;
        }
        Category category = requireCategory(categoryId, memberId);
        ruleRepository.save(CategorizationRule.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .matchType(RuleMatchType.COUNTERPARTY)
            .pattern(counterparty)
            .category(category)
            .priority(0)
            .source(RuleSource.AUTO)
            .build());
    }

    /** Re-run rules over every still-uncategorized transaction; returns the count assigned. */
    @Transactional
    public int recategorizeUncategorized(Long memberId) {
        List<CategorizationRule> rules = ruleRepository.findAllByMemberIdOrderByPriorityDescIdAsc(memberId);
        if (rules.isEmpty()) {
            return 0;
        }
        int assigned = 0;
        for (Transaction tx : transactionRepository.findUncategorizedByMemberId(memberId)) {
            if (apply(tx, rules)) {
                assigned++;
            }
        }
        return assigned;
    }

    // ─── Uncategorized inbox ──────────────────────────────────────────────────

    /** Transactions awaiting a manual category, newest first. */
    public List<TransactionResponse> findUncategorized(Long memberId) {
        return transactionRepository.findUncategorizedByMemberId(memberId).stream()
            .map(TransactionResponse::from)
            .toList();
    }

    public List<CategoryResponse> categoriesFor(Long memberId) {
        return categoryRepository.findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(memberId).stream()
            .map(CategoryResponse::from)
            .toList();
    }

    private Category requireCategory(Long categoryId, Long memberId) {
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
