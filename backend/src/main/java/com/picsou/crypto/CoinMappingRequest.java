package com.picsou.crypto;

import jakarta.validation.constraints.NotBlank;

/**
 * Operator-supplied disambiguation for a crypto ticker CoinGecko couldn't auto-resolve: the coin's
 * CoinGecko page link, from which the backend reads and validates the coin id.
 */
public record CoinMappingRequest(
    @NotBlank String ticker,
    @NotBlank String coingeckoUrl
) {}
