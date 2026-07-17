package com.picsou.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record HoldingResponse(
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal averageBuyIn,
    BigDecimal currentPrice,
    BigDecimal currentValueEur,  // null if currentPrice unknown
    BigDecimal costBasisEur,
    BigDecimal pnlEur,
    BigDecimal pnlPercent,
    Instant priceUpdatedAt,      // when the price was last fetched (null if unknown)
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
