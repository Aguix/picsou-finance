package com.picsou.crypto;

import com.picsou.model.CoinMapping;

import java.time.Instant;

/** A persisted ticker → CoinGecko coin-id mapping, as listed/edited in the management UI. */
public record CoinMappingResponse(
    String ticker,
    String coingeckoId,
    String coinName,
    String resolvedVia,
    Instant updatedAt
) {
    public static CoinMappingResponse from(CoinMapping m) {
        return new CoinMappingResponse(
            m.getTicker(), m.getCoingeckoId(), m.getCoinName(), m.getResolvedVia(), m.getUpdatedAt());
    }
}
