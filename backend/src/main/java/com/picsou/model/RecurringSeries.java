package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A detected (or manually declared) recurring cash movement — a subscription, a direct
 * debit, a regular salary. {@code RecurringDetectionService} groups a member's transactions
 * by normalised counterparty and creates these as {@link RecurringStatus#SUGGESTED}; the user
 * then confirms or ignores them. Confirmed series drive the upcoming-due-date calendar.
 */
@Entity
@Table(name = "recurring_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringSeries extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 255)
    private String label;

    @Column(length = 255)
    private String counterparty;

    /** Signed like a transaction: negative for an outflow (debit), positive for income. */
    @Column(name = "expected_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "recurring_cadence")
    private RecurringCadence cadence;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "last_seen_date")
    private LocalDate lastSeenDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "recurring_status")
    @Builder.Default
    private RecurringStatus status = RecurringStatus.SUGGESTED;
}
