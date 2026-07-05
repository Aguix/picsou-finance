package com.picsou.crypto;

/** The persisted ticker → CoinGecko coin-id mapping, echoed back after a manual disambiguation. */
public record CoinMappingResponse(String ticker, String coingeckoId, String coinName) {}
