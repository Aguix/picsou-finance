package com.picsou.dto;

import com.picsou.model.FinancialAsset;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A {@code financial_asset} registry row, for the standing mapping/verification UI (holding detail)
 * and the registry table. Returned after applying a mapping so the client can reflect the new
 * status/link without a reload.
 *
 * <p>One field per aggregator ref, mirroring the table: the registry column shows at a glance which
 * aggregators can quote the asset — and therefore whether it has a fallback if one of them is down.
 */
public record AssetResponse(
    String symbol,
    String name,
    String type,
    String status,
    String coingeckoId,
    String yahooSymbol,
    String coinmarketcapId,
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
            a.getCoinmarketcapId(),
            a.getLastEurValue(),
            a.getPriceSyncedAt()
        );
    }
}
