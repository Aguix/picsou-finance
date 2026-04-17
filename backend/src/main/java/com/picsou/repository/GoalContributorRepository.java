package com.picsou.repository;

import com.picsou.model.GoalContributor;
import com.picsou.model.GoalContributorId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalContributorRepository extends JpaRepository<GoalContributor, GoalContributorId> {
}
