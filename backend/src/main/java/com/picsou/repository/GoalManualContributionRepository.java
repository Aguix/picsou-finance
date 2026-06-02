package com.picsou.repository;

import com.picsou.model.GoalManualContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoalManualContributionRepository extends JpaRepository<GoalManualContribution, Long> {
    List<GoalManualContribution> findByGoalId(Long goalId);
    Optional<GoalManualContribution> findByGoalIdAndYearMonth(Long goalId, String yearMonth);
    List<GoalManualContribution> findByGoalIdAndMemberId(Long goalId, Long memberId);
}
