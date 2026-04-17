package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "goal_contributor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GoalContributor {

    @EmbeddedId
    private GoalContributorId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("goalId")
    @JoinColumn(name = "goal_id")
    private Goal goal;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("memberId")
    @JoinColumn(name = "member_id")
    private FamilyMember member;
}
