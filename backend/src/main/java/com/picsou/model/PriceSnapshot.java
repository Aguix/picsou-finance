package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "price_snapshot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"asset_id", "date"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The asset this daily price is for — identity by FK, not a ticker string (see V56). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private FinancialAsset asset;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "price_eur", nullable = false, precision = 20, scale = 8)
    private BigDecimal priceEur;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
