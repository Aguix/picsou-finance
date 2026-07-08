package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A priceable asset (crypto coin, stock, ETF) and its links to the price aggregators that can
 * quote it — the dynamic replacement for the hardcoded ticker registries the providers used to
 * carry. One nullable column per aggregator (exactly one external ref per (asset, aggregator));
 * a row is priceable on the aggregators whose ref is set. Rows are resolved on demand
 * (a single dominant market-cap match auto-resolves) or supplied by the user for ambiguous
 * symbols. See {@link com.picsou.service.FinancialAssetService}.
 *
 * <p>A row can also mark a symbol as <b>worthless</b> ({@code status == WORTHLESS}, no aggregator
 * ref): a delisted coin no aggregator can price, valued at a known zero instead of left silently
 * unpriced.</p>
 */
@Entity
@Table(name = "financial_asset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialAsset extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Uppercase internal ticker as seen in holdings and imports (BTC, IWDA.AS, …). Unique. */
    @Column(nullable = false, length = 30, unique = true)
    private String symbol;

    /** Human name from the resolving aggregator (or the user); informational only. */
    @Column(length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AssetType type = AssetType.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AssetStatus status = AssetStatus.PENDING;

    @Column(length = 12)
    private String isin;

    /** CoinGecko coin id used verbatim in API calls (e.g. "bitcoin", "matic-network"). */
    @Column(name = "coingecko_id", length = 100)
    private String coingeckoId;

    /** Yahoo Finance symbol used verbatim in API calls (e.g. "IWDA.AS", "MC.PA"). */
    @Column(name = "yahoo_symbol", length = 50)
    private String yahooSymbol;

    /** Last known EUR price — survives restarts, unlike the in-memory price cache. */
    @Column(name = "last_eur_value", precision = 20, scale = 8)
    private BigDecimal lastEurValue;

    @Column(name = "price_synced_at")
    private Instant priceSyncedAt;

    /** True when the symbol is intentionally valued at zero rather than priced via an aggregator. */
    @Transient
    public boolean isWorthless() {
        return status == AssetStatus.WORTHLESS;
    }
}
