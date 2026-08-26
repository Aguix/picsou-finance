package com.picsou.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record HoldingResponse(
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal averageBuyIn,
    BigDecimal currentPrice,
    String quoteCurrency,
    BigDecimal currentValueEur,  // null if currentPrice unknown
    BigDecimal costBasisEur,
    BigDecimal pnlEur,
    BigDecimal pnlPercent,
    Instant priceUpdatedAt,      // when the price was last fetched (null if unknown)
    // The day currentPrice is for, and whether it is a recorded price rather than a live quote.
    // Both null/false when no price could be resolved at all. The client shows the figure either
    // way and marks a stale one, so an aggregator outage degrades the price's age instead of
    // blanking the line.
    LocalDate priceAsOf,
    boolean priceStale,
    String assetType,            // registry AssetType of the underlying asset (CRYPTO, STOCK, …)
    String assetStatus,          // registry AssetStatus: PENDING/AUTO/USER/WORTHLESS — drives the
                                 // resolution badge + aggregator-link editor in the holding detail
    // One field per aggregator ref, mirroring the registry: the standing editor shows which
    // aggregators can quote this asset (and therefore whether it has a price fallback). Null = that
    // aggregator can't quote it. Kept in sync with the aggregator columns of financial_asset.
    String coingeckoId,
    String coinmarketcapId,
    String yahooSymbol
) {}
