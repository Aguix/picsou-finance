package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A price aggregator behind {@link com.picsou.port.PriceProviderPort} (CoinGecko, Yahoo, …),
 * persisted so its API credentials ({@link AggregatorSession}) and on/off state can live in the DB
 * rather than in config. One row per provider, linked to the code by {@link #aggregatorKey} (the
 * value of {@code PriceProviderPort.aggregatorKey()}).
 *
 * <p>App-global config — deliberately not member-scoped: a price API key is instance-wide, unlike
 * the per-member bank/broker sessions.</p>
 */
@Entity
@Table(name = "aggregator")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aggregator extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable key matching {@link com.picsou.port.PriceProviderPort#aggregatorKey()} (e.g. "coingecko"). */
    @Column(name = "aggregator_key", nullable = false, length = 50, unique = true)
    private String aggregatorKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** False pauses the whole aggregator (and, transitively, its sessions) without deleting it. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
