package com.picsou.dto;

import com.picsou.model.FinancialAsset;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A {@code financial_asset} registry row, for the standing mapping/verification UI (holding detail).
 * Returned after applying a mapping so the client can reflect the new status/link without a reload.
 */
public record AssetResponse(
    String symbol,
    String name,
    String type,
    String status,
    String coingeckoId,
    String yahooSymbol,
    BigDecimal lastEurValue,
    Instant priceSyncedAt
) {
    public static AssetResponse from(FinancialAsset a) {
        return new AssetResponse(
            a.getSymbol(),
            a.getName(),
            a.getType() != null ? a.getType().name() : null,
            a.getStatus() != null ? a.getStatus().name() : null,
            a.getCoingeckoId(),
            a.getYahooSymbol(),
            a.getLastEurValue(),
            a.getPriceSyncedAt()
        );
    }
}
