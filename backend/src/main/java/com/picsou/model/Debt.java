package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "debt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Debt extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private Account linkedAccount;

    @Column(name = "borrowed_amount", nullable = false, precision = 20, scale = 8)
    private BigDecimal borrowedAmount;

    @Column(name = "interest_rate", precision = 5, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "monthly_payment", precision = 20, scale = 8)
    private BigDecimal monthlyPayment;

    @Column(name = "lender_name", length = 100)
    private String lenderName;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "insurance_monthly", precision = 20, scale = 8)
    private BigDecimal insuranceMonthly;

    @Column(name = "file_fees", precision = 20, scale = 8)
    private BigDecimal fileFees;
}
