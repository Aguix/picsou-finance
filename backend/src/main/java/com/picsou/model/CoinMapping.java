package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A persisted ticker → CoinGecko coin-id mapping. This is the dynamic replacement for the
 * hardcoded map CoinGeckoPriceProvider used to carry: rows are resolved on demand from CoinGecko
 * (a single dominant market-cap match auto-resolves) or supplied by the user for ambiguous
 * symbols, then cached here forever. See {@link com.picsou.service.CoinMappingService}.
 */
@Entity
@Table(name = "coin_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoinMapping {

    /** Uppercase ticker (BTC, ETH, …) — the natural key. */
    @Id
    @Column(name = "ticker", length = 30)
    private String ticker;

    /** CoinGecko coin id used verbatim in API calls (e.g. "bitcoin", "matic-network"). */
    @Column(name = "coingecko_id", nullable = false, length = 100)
    private String coingeckoId;

    /** Human name from CoinGecko at resolution time; informational only. */
    @Column(name = "coin_name", length = 120)
    private String coinName;

    /** How the mapping was decided: AUTO (dominant market-cap match) or USER (operator-supplied). */
    @Column(name = "resolved_via", nullable = false, length = 10)
    @Builder.Default
    private String resolvedVia = "AUTO";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
