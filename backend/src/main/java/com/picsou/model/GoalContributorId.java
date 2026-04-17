package com.picsou.model;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class GoalContributorId implements Serializable {
    private Long goalId;
    private Long memberId;
}
