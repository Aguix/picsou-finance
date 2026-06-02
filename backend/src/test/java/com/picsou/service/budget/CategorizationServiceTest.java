package com.picsou.service.budget;

import com.picsou.model.CategorizationRule;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;
import com.picsou.model.Transaction;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationServiceTest {

    @Mock CategorizationRuleRepository ruleRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;

    @InjectMocks CategorizationService service;

    private static final Long MEMBER_ID = 10L;

    private Category category(Long id, CategoryKind kind, String name) {
        return Category.builder().id(id).kind(kind).name(name).build();
    }

    private CategorizationRule rule(RuleMatchType type, String pattern, Category cat, int priority) {
        return CategorizationRule.builder()
            .matchType(type).pattern(pattern).category(cat).priority(priority).source(RuleSource.USER)
            .build();
    }

    private Transaction tx(String counterparty, String description) {
        return Transaction.builder().counterparty(counterparty).description(description).build();
    }

    @Test
    void apply_counterpartyRule_matchesExactlyIgnoringCase() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR", "CB CARREFOUR PARIS");
        boolean matched = service.apply(t, rules);

        assertThat(matched).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(groceries);
    }

    @Test
    void apply_keywordRule_matchesSubstringInDescription() {
        Category subs = category(2L, CategoryKind.EXPENSE, "Abonnements");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORD, "netflix", subs, 0));

        Transaction t = tx(null, "PRLV NETFLIX.COM");
        assertThat(service.apply(t, rules)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(subs);
    }

    @Test
    void apply_higherPriorityRuleWins() {
        Category generic = category(3L, CategoryKind.EXPENSE, "Divers");
        Category transport = category(4L, CategoryKind.EXPENSE, "Transport");
        // Service is given rules already ordered priority-desc by the repository.
        List<CategorizationRule> rules = List.of(
            rule(RuleMatchType.KEYWORD, "sncf", transport, 10),
            rule(RuleMatchType.KEYWORD, "cb", generic, 0)
        );

        Transaction t = tx("SNCF", "CB SNCF INTERNET");
        service.apply(t, rules);
        assertThat(t.getCategoryRef()).isEqualTo(transport);
    }

    @Test
    void apply_noMatch_leavesUncategorized() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("AMAZON", "AMZN MKTPLACE");
        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isNull();
    }

    @Test
    void apply_neverOverridesExistingCategory() {
        Category existing = category(5L, CategoryKind.EXPENSE, "Manual");
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR", "CB CARREFOUR");
        t.setCategoryRef(existing);

        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isEqualTo(existing);
    }

    @Test
    void learnRule_createsAutoCounterpartyRule_whenNoneExists() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.COUNTERPARTY, "Carrefour")).thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(groceries));
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(new FamilyMember());

        service.learnRule("Carrefour", 1L, MEMBER_ID);

        ArgumentCaptor<CategorizationRule> captor = ArgumentCaptor.forClass(CategorizationRule.class);
        verify(ruleRepository).save(captor.capture());
        CategorizationRule saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(RuleSource.AUTO);
        assertThat(saved.getMatchType()).isEqualTo(RuleMatchType.COUNTERPARTY);
        assertThat(saved.getPattern()).isEqualTo("Carrefour");
        assertThat(saved.getCategory()).isEqualTo(groceries);
    }

    @Test
    void learnRule_isIdempotent_whenRuleAlreadyExists() {
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.COUNTERPARTY, "Carrefour"))
            .thenReturn(Optional.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour",
                category(1L, CategoryKind.EXPENSE, "Courses"), 0)));

        service.learnRule("Carrefour", 1L, MEMBER_ID);

        verify(ruleRepository, never()).save(any());
    }
}
