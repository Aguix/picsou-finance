package com.picsou.dto;

/**
 * "Insight" payload for a single security shown in the holding detail modal.
 *
 * @param ticker      the requested ticker (uppercased)
 * @param assetType   "ETF" | "STOCK" | "CRYPTO" | "UNKNOWN"
 * @param composition ETF breakdowns, or null when the asset is not an ETF or
 *                    when no supported issuer could resolve it
 */
public record SecurityInsightResponse(
    String ticker,
    String assetType,
    EtfComposition composition
) {}
