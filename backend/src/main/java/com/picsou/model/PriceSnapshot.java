package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "price_snapshot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "date"})
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

    @Column(nullable = false, length = 30)
    private String ticker;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "price_eur", nullable = false, precision = 20, scale = 8)
    private BigDecimal priceEur;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
