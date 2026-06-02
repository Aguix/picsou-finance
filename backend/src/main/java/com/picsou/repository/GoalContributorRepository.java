package com.picsou.repository;

import com.picsou.model.GoalContributor;
import com.picsou.model.GoalContributorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoalContributorRepository extends JpaRepository<GoalContributor, GoalContributorId> {
    List<GoalContributor> findByGoalId(Long goalId);
}
