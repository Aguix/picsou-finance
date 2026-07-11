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
    String coingeckoId           // linked CoinGecko id, or null when unresolved/worthless
) {}
