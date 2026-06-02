package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "goal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Goal extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal targetAmount;

    @Column(nullable = false)
    private LocalDate deadline;

    /** Optional backfill start ("YYYY-MM"). When earlier than createdAt, the calendar extends back to it. */
    @Column(name = "history_start_month", length = 7)
    private String historyStartMonth;

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "goal_account",
        joinColumns = @JoinColumn(name = "goal_id"),
        inverseJoinColumns = @JoinColumn(name = "account_id")
    )
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();
}
