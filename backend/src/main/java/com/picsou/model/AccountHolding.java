package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_holding")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountHolding extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 30)
    private String ticker;

    @Column(length = 100)
    private String name;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(name = "average_buy_in", precision = 20, scale = 8)
    private BigDecimal averageBuyIn;

    @Column(name = "current_price", precision = 20, scale = 8)
    private BigDecimal currentPrice;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
