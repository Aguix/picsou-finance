package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "real_estate_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealEstateMetadata extends AuditableEntity {

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

    @Column(name = "purchase_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "surface_area", precision = 10, scale = 2)
    private BigDecimal surfaceArea;

    @Column(length = 500)
    private String address;

    @Column(name = "property_type", length = 50)
    private String propertyType;

    @Column(name = "rental_income", precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal rentalIncome = BigDecimal.ZERO;
}
