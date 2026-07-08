package com.picsou.model;

/**
 * Broad asset class of a {@link FinancialAsset} — a routing hint (which aggregators are worth
 * asking) and a UI grouping, not a hard constraint: pricing routes on which aggregator refs are
 * set, so an UNKNOWN asset with a Yahoo symbol still prices fine.
 */
public enum AssetType {
    CRYPTO,
    STOCK,
    ETF,
    /** Seeded from pre-existing tickers or not yet classified; refined as sources declare it. */
    UNKNOWN
}
